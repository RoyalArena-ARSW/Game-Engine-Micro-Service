package edu.eci.arsw.RoyalArena.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import edu.eci.arsw.RoyalArena.client.DeckAndCardsClient;
import edu.eci.arsw.RoyalArena.dto.MatchFoundDTO;
import edu.eci.arsw.RoyalArena.exception.DeckServiceUnavailableException;
import edu.eci.arsw.RoyalArena.exception.NoActiveDeckException;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;
import edu.eci.arsw.RoyalArena.model.GameMatch;
import edu.eci.arsw.RoyalArena.model.WaitingPlayer;
import edu.eci.arsw.RoyalArena.model.enums.JoinResult;
import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.client.DeckAndCardsClient;
import edu.eci.arsw.RoyalArena.exception.DeckServiceUnavailableException;
import edu.eci.arsw.RoyalArena.exception.NoActiveDeckException;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;
import edu.eci.arsw.RoyalArena.model.WaitingPlayer;

import lombok.extern.slf4j.Slf4j;

/**
 * Cola de emparejamiento. Cuando dos jugadores piden partida, los junta,
 * crea el GameMatch y notifica a ambos por WebSocket.
 *
 * ============ CONCURRENCIA ============
 *
 * El emparejamiento es un problema clásico de "check-then-act": mirar si hay
 * alguien esperando y decidir en consecuencia debe ser ATÓMICO. Si no lo es,
 * dos jugadores concurrentes pueden ver ambos la cola vacía y quedarse los dos
 * esperando (nunca se emparejan entre sí), o el mismo jugador puede entrar dos
 * veces y emparejarse consigo mismo.
 *
 * Usar una ConcurrentLinkedQueue NO basta: sus operaciones son atómicas por
 * separado, pero la SECUENCIA poll()+offer() no lo es.
 *
 * Por eso la decisión (buscar oponente / encolar) va dentro de una sección
 * crítica con lock. Es deliberadamente MÍNIMA: lo costoso (crear la partida,
 * arrancar el loop, notificar) ocurre FUERA del lock, así la contención es
 * despreciable.
 *
 * Contraste con el GameEngineService: allí la concurrencia se resuelve SIN
 * locks (single-writer + colas concurrentes) porque cada partida es
 * independiente. Aquí hay un recurso realmente compartido —la cola— y un lock
 * es la herramienta correcta.
 */
@Slf4j
@Service
public class MatchmakingService {

    /** Tiempo tras el cual una entrada en cola se considera abandonada. */
    private static final long STALE_THRESHOLD_MS = 60_000;

    private final Deque<WaitingPlayer> queue = new ArrayDeque<>();
    private final Set<Long> queuedUsers = new HashSet<>();
    private final Object lock = new Object();

    private final GameEngineService gameEngine;
    private final TestDataFactory testDataFactory;
    private final DeckAndCardsClient deckClient;
    private final SimpMessagingTemplate messagingTemplate;

    public MatchmakingService(GameEngineService gameEngine,
                              DeckAndCardsClient deckClient,
                              SimpMessagingTemplate messagingTemplate) {
        this.gameEngine = gameEngine;
        this.testDataFactory = new TestDataFactory();
        this.deckClient = deckClient;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * El jugador pide partida. Si hay alguien esperando, se emparejan y se crea
     * la partida. Si no, queda en cola.
     */
    public JoinResult joinQueue(Long userId) {
        List<CardSnapshot> myDeck;
        try {
            myDeck = deckClient.fetchActiveDeck(userId);
        } catch (NoActiveDeckException e) {
            log.warn("User {} tried to queue without an active deck", userId);
            return JoinResult.NO_ACTIVE_DECK;
        } catch (DeckServiceUnavailableException e) {
            log.error("Deck service unavailable for user {}: {}", userId, e.getMessage());
            return JoinResult.DECK_SERVICE_UNAVAILABLE;
        }

        WaitingPlayer me = new WaitingPlayer(userId, myDeck, System.currentTimeMillis());
        WaitingPlayer opponent;

        // ===== SECCIÓN CRÍTICA: decidir emparejar o encolar =====
        synchronized (lock) {
            if (queuedUsers.contains(userId)) {
                return JoinResult.ALREADY_QUEUED;
            }
            opponent = pollValidOpponent();
            if (opponent == null) {
                queue.addLast(me);
                queuedUsers.add(userId);
            }
        }
        // ===== FIN DE LA SECCIÓN CRÍTICA =====

        if (opponent == null) {
            log.info("User {} queued for matchmaking (queue size: {})", userId, getQueueSize());
            return JoinResult.QUEUED;
        }

        createAndNotify(opponent, me);
        return JoinResult.MATCHED;
    }


    /**
     * Saca de la cola al primer jugador NO obsoleto, descartando fantasmas.
     * Debe llamarse SIEMPRE con el lock tomado.
     */
    private WaitingPlayer pollValidOpponent() {
        long now = System.currentTimeMillis();
        while (!queue.isEmpty()) {
            WaitingPlayer candidate = queue.pollFirst();
            queuedUsers.remove(candidate.userId());
            if (now - candidate.queuedAtMs() > STALE_THRESHOLD_MS) {
                log.debug("Discarding stale queue entry for user {}", candidate.userId());
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * El jugador cancela su búsqueda.
     */
    public boolean leaveQueue(Long userId) {
        synchronized (lock) {
            if (!queuedUsers.remove(userId)) {
                return false;
            }
            Iterator<WaitingPlayer> it = queue.iterator();
            while (it.hasNext()) {
                if (it.next().userId().equals(userId)) {
                    it.remove();
                    break;
                }
            }
        }
        log.info("User {} left the matchmaking queue", userId);
        return true;
    }

    public int getQueueSize() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public boolean isQueued(Long userId) {
        synchronized (lock) {
            return queuedUsers.contains(userId);
        }
    }

    /**
     * Crea la partida y notifica a ambos jugadores.
     *
     * Nota: notificamos ANTES de arrancar el loop para que los clientes puedan
     * suscribirse al topic de la partida. Aun así, que se pierdan uno o dos
     * ticks es inofensivo: cada tick emite el estado COMPLETO (no deltas), así
     * que el primer snapshot que reciban ya trae todo.
     */
    private void createAndNotify(WaitingPlayer playerA, WaitingPlayer playerB) {
        GameMatch match = gameEngine.createMatch(
                playerA.userId(), playerA.deck(),
                playerB.userId(), playerB.deck());

        notifyPlayer(playerA.userId(), match.getMatchId(), playerB.userId(), Team.TEAM_A);
        notifyPlayer(playerB.userId(), match.getMatchId(), playerA.userId(), Team.TEAM_B);

        gameEngine.startMatch(match.getMatchId());
        log.info("Matchmaking paired {} vs {} → match {}",
                playerA.userId(), playerB.userId(), match.getMatchId());
    }

    private void notifyPlayer(Long userId, String matchId, Long opponentId, Team team) {
        messagingTemplate.convertAndSend(
                "/topic/matchmaking/" + userId,
                new MatchFoundDTO(matchId, opponentId, team.name()));
    }
}