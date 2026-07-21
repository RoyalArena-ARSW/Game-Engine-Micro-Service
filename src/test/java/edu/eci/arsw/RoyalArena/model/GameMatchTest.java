package edu.eci.arsw.RoyalArena.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.eci.arsw.RoyalArena.model.enums.MatchStatus;
import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.records.PlayerAction;
import edu.eci.arsw.RoyalArena.model.records.Position;

class GameMatchTest {

    private GameMatch match;

    private List<CardSnapshot> deckOf8() {
        List<CardSnapshot> deck = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            deck.add(CardSnapshot.builder()
                    .cardId(i).name("Card" + i).type("TROOP").elixirCost(3)
                    .damage(100).health(500).attackSpeed(1.0).movementSpeed(1.0)
                    .attackRange(1.0).target("GROUND").unitCount(1)
                    .deploymentType("OWN_SIDE").build());
        }
        return deck;
    }

    @BeforeEach
    void setUp() {
        match = new GameMatch(
                new PlayerState(1L, Team.TEAM_A, deckOf8()),
                new PlayerState(2L, Team.TEAM_B, deckOf8()),
                180.0, 60);
    }

    @Test
    @DisplayName("Nace en WAITING, con el tiempo completo y sin ganador")
    void startsInWaitingState() {
        assertThat(match.getStatus()).isEqualTo(MatchStatus.WAITING);
        assertThat(match.getRemainingSeconds()).isEqualTo(180.0);
        assertThat(match.getWinner()).isNull();
        assertThat(match.isInProgress()).isFalse();
        assertThat(match.getMatchId()).isNotBlank();
    }

    @Test
    @DisplayName("Dos partidas tienen ids distintos")
    void matchesHaveUniqueIds() {
        GameMatch other = new GameMatch(
                new PlayerState(3L, Team.TEAM_A, deckOf8()),
                new PlayerState(4L, Team.TEAM_B, deckOf8()),
                180.0, 60);
        assertThat(match.getMatchId()).isNotEqualTo(other.getMatchId());
    }

    @Test
    @DisplayName("Encuentra a sus jugadores y solo a los suyos")
    void findsItsPlayers() {
        assertThat(match.findPlayer(1L)).isNotNull();
        assertThat(match.findPlayer(1L).getTeam()).isEqualTo(Team.TEAM_A);
        assertThat(match.findPlayer(2L).getTeam()).isEqualTo(Team.TEAM_B);
        assertThat(match.findPlayer(999L)).isNull();
    }

    @Test
    @DisplayName("Cada equipo tiene un jugador en 1v1")
    void oneVsOneHasOnePlayerPerTeam() {
        assertThat(match.getPlayersOf(Team.TEAM_A)).hasSize(1);
        assertThat(match.getPlayersOf(Team.TEAM_B)).hasSize(1);
    }

    @Test
    @DisplayName("isInProgress refleja el estado")
    void inProgressReflectsStatus() {
        match.setStatus(MatchStatus.IN_PROGRESS);
        assertThat(match.isInProgress()).isTrue();

        match.setStatus(MatchStatus.FINISHED);
        assertThat(match.isInProgress()).isFalse();
    }

    @Test
    @DisplayName("Las acciones encoladas se drenan en orden FIFO")
    void actionsAreQueuedInOrder() {
        match.enqueueAction(new PlayerAction(1L, 1L, new Position(9, 5), 1000L));
        match.enqueueAction(new PlayerAction(1L, 2L, new Position(9, 6), 2000L));

        assertThat(match.getPendingActions()).hasSize(2);
        assertThat(match.getPendingActions().poll().cardId()).isEqualTo(1L);
        assertThat(match.getPendingActions().poll().cardId()).isEqualTo(2L);
        assertThat(match.getPendingActions()).isEmpty();
    }

    /**
     * El patrón single-writer del motor depende de que encolar sea seguro desde
     * múltiples threads (los de WebSocket). Este test verifica que la
     * ConcurrentLinkedQueue no pierde acciones bajo contención.
     */
    @Test
    @DisplayName("Encolar desde muchos threads no pierde ninguna accion")
    void concurrentEnqueuesLoseNoActions() throws InterruptedException {
        final int threads = 50;
        final int actionsPerThread = 20;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < actionsPerThread; i++) {
                        match.enqueueAction(new PlayerAction(
                                1L, 1L, new Position(9, 5), System.currentTimeMillis()));
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

        assertThat(match.getPendingActions()).hasSize(threads * actionsPerThread);
    }

    @Test
    @DisplayName("Una partida nueva trae su propio grid de obstaculos vacio")
    void hasItsOwnObstacles() {
        assertThat(match.getObstacles()).isNotNull();
        assertThat(match.getObstacles().isBlocked(new Cell(9, 9))).isFalse();
    }

    @Test
    @DisplayName("Los obstaculos de una partida no afectan a otra")
    void obstaclesAreIsolatedPerMatch() {
        GameMatch other = new GameMatch(
                new PlayerState(3L, Team.TEAM_A, deckOf8()),
                new PlayerState(4L, Team.TEAM_B, deckOf8()),
                180.0, 60);

        match.getObstacles().addObstacle("X", new Position(9.5, 9.5), 1.5);

        assertThat(match.getObstacles().isBlocked(new Cell(9, 9))).isTrue();
        assertThat(other.getObstacles().isBlocked(new Cell(9, 9)))
                .as("cada partida tiene sus propios obstaculos")
                .isFalse();
    }
}