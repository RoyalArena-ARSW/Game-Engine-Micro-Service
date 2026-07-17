package edu.eci.arsw.RoyalArena.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import edu.eci.arsw.RoyalArena.client.DeckAndCardsClient;
import edu.eci.arsw.RoyalArena.exception.NoActiveDeckException;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;
import edu.eci.arsw.RoyalArena.model.GameMatch;
import edu.eci.arsw.RoyalArena.model.PlayerState;
import edu.eci.arsw.RoyalArena.model.enums.JoinResult;
import edu.eci.arsw.RoyalArena.model.enums.Team;

/**
 * Tests del matchmaking, con foco en la CONCURRENCIA.
 *
 * El emparejamiento es un "check-then-act": mirar si hay alguien esperando y
 * decidir en consecuencia debe ser atómico. Una ConcurrentLinkedQueue NO basta
 * (sus operaciones son atómicas por separado, pero la secuencia poll()+offer()
 * no lo es): dos jugadores concurrentes podrían ver ambos la cola vacía y
 * quedarse los dos esperando.
 *
 * El test de estrés de abajo es el que valida que la sección crítica funciona.
 */
class MatchmakingServiceTest {

    private GameEngineService gameEngine;
    private DeckAndCardsClient deckClient;
    private SimpMessagingTemplate messagingTemplate;
    private MatchmakingService matchmaking;

    /** Pares emparejados, registrados por el mock de createMatch. */
    private ConcurrentLinkedQueue<long[]> createdPairs;

    @BeforeEach
    void setUp() {
        gameEngine = mock(GameEngineService.class);
        deckClient = mock(DeckAndCardsClient.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        createdPairs = new ConcurrentLinkedQueue<>();

        // Todos los usuarios tienen mazo válido, salvo que un test diga otra cosa
        when(deckClient.fetchActiveDeck(anyLong())).thenReturn(dummyDeck());

        // createMatch registra el par y devuelve un GameMatch real
        when(gameEngine.createMatch(anyLong(), anyList(), anyLong(), anyList()))
                .thenAnswer(inv -> {
                    Long userA = inv.getArgument(0);
                    Long userB = inv.getArgument(2);
                    createdPairs.add(new long[]{userA, userB});
                    return new GameMatch(
                            new PlayerState(userA, Team.TEAM_A, dummyDeck()),
                            new PlayerState(userB, Team.TEAM_B, dummyDeck()),
                            180.0);
                });

        matchmaking = new MatchmakingService(gameEngine, deckClient, messagingTemplate);
    }

    /** Mazo mínimo de 8 cartas: PlayerState reparte 4 a la mano y 4 al ciclo. */
    private List<CardSnapshot> dummyDeck() {
        List<CardSnapshot> deck = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            deck.add(CardSnapshot.builder()
                    .cardId(i)
                    .name("Card" + i)
                    .type("TROOP")
                    .elixirCost(3)
                    .damage(100)
                    .health(500)
                    .attackSpeed(1.0)
                    .movementSpeed(1.0)
                    .attackRange(1.0)
                    .target("GROUND")
                    .unitCount(1)
                    .deploymentType("OWN_SIDE")
                    .build());
        }
        return deck;
    }

    // ===== Comportamiento básico =====

    @Test
    @DisplayName("El primer jugador queda en cola")
    void firstPlayerGetsQueued() {
        assertThat(matchmaking.joinQueue(1L)).isEqualTo(JoinResult.QUEUED);
        assertThat(matchmaking.getQueueSize()).isEqualTo(1);
        assertThat(matchmaking.isQueued(1L)).isTrue();
    }

    @Test
    @DisplayName("El segundo jugador se empareja con el primero y la cola queda vacia")
    void secondPlayerGetsMatched() {
        matchmaking.joinQueue(1L);
        assertThat(matchmaking.joinQueue(2L)).isEqualTo(JoinResult.MATCHED);
        assertThat(matchmaking.getQueueSize()).isZero();
        assertThat(createdPairs).hasSize(1);
    }

    @Test
    @DisplayName("Un jugador no puede encolarse dos veces (ni emparejarse consigo mismo)")
    void cannotQueueTwice() {
        assertThat(matchmaking.joinQueue(1L)).isEqualTo(JoinResult.QUEUED);
        assertThat(matchmaking.joinQueue(1L)).isEqualTo(JoinResult.ALREADY_QUEUED);
        assertThat(matchmaking.getQueueSize()).isEqualTo(1);
        assertThat(createdPairs).as("no deberia haberse creado ninguna partida").isEmpty();
    }

    @Test
    @DisplayName("Cancelar la busqueda saca al jugador de la cola")
    void leaveQueueRemovesPlayer() {
        matchmaking.joinQueue(1L);
        assertThat(matchmaking.leaveQueue(1L)).isTrue();
        assertThat(matchmaking.getQueueSize()).isZero();
        assertThat(matchmaking.isQueued(1L)).isFalse();
    }

    @Test
    @DisplayName("Cancelar sin estar en cola no hace nada")
    void leaveQueueWhenNotQueued() {
        assertThat(matchmaking.leaveQueue(99L)).isFalse();
    }

    // ===== Fallas de mazo =====

    @Test
    @DisplayName("Sin mazo activo, el jugador NO entra a la cola")
    void noActiveDeckDoesNotTouchTheQueue() {
        when(deckClient.fetchActiveDeck(99L))
                .thenThrow(new NoActiveDeckException("no deck"));

        assertThat(matchmaking.joinQueue(99L)).isEqualTo(JoinResult.NO_ACTIVE_DECK);
        assertThat(matchmaking.getQueueSize())
                .as("una falla de mazo no debe ensuciar la cola")
                .isZero();
    }

    @Test
    @DisplayName("Una falla de mazo no arrastra al oponente que ya esperaba")
    void deckFailureDoesNotAffectWaitingPlayer() {
        matchmaking.joinQueue(1L); // queda esperando

        when(deckClient.fetchActiveDeck(99L))
                .thenThrow(new NoActiveDeckException("no deck"));
        matchmaking.joinQueue(99L); // falla

        assertThat(matchmaking.getQueueSize())
                .as("el jugador 1 deberia seguir esperando intacto")
                .isEqualTo(1);
        assertThat(matchmaking.isQueued(1L)).isTrue();
    }

    // ===== EL TEST DE CONCURRENCIA =====

    @Test
    @DisplayName("100 jugadores concurrentes se emparejan en 50 partidas exactas, sin duplicados")
    void concurrentJoinsPairPlayersExactlyOnce() throws InterruptedException {
        final int players = 100;

        // startGate: todos los threads esperan aquí y arrancan a la vez, para
        // maximizar la contención sobre la cola.
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(players);
        ExecutorService pool = Executors.newFixedThreadPool(players);

        AtomicInteger queued = new AtomicInteger();
        AtomicInteger matched = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (long userId = 1; userId <= players; userId++) {
            final long id = userId;
            pool.submit(() -> {
                try {
                    startGate.await();
                    JoinResult result = matchmaking.joinQueue(id);
                    switch (result) {
                        case QUEUED -> queued.incrementAndGet();
                        case MATCHED -> matched.incrementAndGet();
                        default -> other.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown(); // ¡ya!
        boolean finished = doneGate.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("los joins no deberian bloquearse").isTrue();

        // Nadie debió fallar
        assertThat(other.get()).isZero();

        // Cada MATCHED consumió a un QUEUED: 50 y 50, cola vacía al final.
        // SIN el lock, dos threads podrían ver la cola vacía a la vez y ambos
        // encolar → quedarían jugadores colgados y este assert fallaría.
        assertThat(queued.get()).isEqualTo(players / 2);
        assertThat(matched.get()).isEqualTo(players / 2);
        assertThat(matchmaking.getQueueSize())
                .as("no deberia quedar nadie esperando")
                .isZero();

        // Exactamente 50 partidas
        assertThat(createdPairs).hasSize(players / 2);

        // Cada jugador aparece EXACTAMENTE una vez, y nadie contra sí mismo
        Set<Long> seen = new HashSet<>();
        for (long[] pair : createdPairs) {
            assertThat(pair[0]).as("nadie deberia emparejarse consigo mismo").isNotEqualTo(pair[1]);
            assertThat(seen.add(pair[0])).as("usuario %d emparejado dos veces", pair[0]).isTrue();
            assertThat(seen.add(pair[1])).as("usuario %d emparejado dos veces", pair[1]).isTrue();
        }
        assertThat(seen).hasSize(players);
    }

    @Test
    @DisplayName("El mismo jugador pidiendo partida en paralelo solo se encola una vez")
    void concurrentJoinsOfSamePlayerDoNotDuplicate() throws InterruptedException {
        final int attempts = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);

        AtomicInteger queued = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (matchmaking.joinQueue(7L) == JoinResult.QUEUED) {
                        queued.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // Solo uno de los 50 intentos debió encolar; el resto ALREADY_QUEUED
        assertThat(queued.get()).isEqualTo(1);
        assertThat(matchmaking.getQueueSize()).isEqualTo(1);
        assertThat(createdPairs).as("nunca debio emparejarse consigo mismo").isEmpty();
    }
}