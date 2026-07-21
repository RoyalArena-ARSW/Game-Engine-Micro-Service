package edu.eci.arsw.RoyalArena.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import edu.eci.arsw.RoyalArena.dto.MatchSnapshotDTO;
import edu.eci.arsw.RoyalArena.events.MatchEventPublisher;
import edu.eci.arsw.RoyalArena.events.MatchFinishedEvent;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;
import edu.eci.arsw.RoyalArena.model.Cell;
import edu.eci.arsw.RoyalArena.model.DeployedUnit;
import edu.eci.arsw.RoyalArena.model.GameMatch;
import edu.eci.arsw.RoyalArena.model.PlayerState;
import edu.eci.arsw.RoyalArena.model.TowerState;
import edu.eci.arsw.RoyalArena.model.enums.MatchStatus;
import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.TowerType;
import edu.eci.arsw.RoyalArena.model.records.PlayerAction;
import edu.eci.arsw.RoyalArena.model.records.Position;
import edu.eci.arsw.RoyalArena.pathfinding.AStarPathfinder;
import edu.eci.arsw.RoyalArena.pathfinding.GameGrid;

/**
 * Tests del motor.
 *
 * ESTRATEGIA: no usamos el ScheduledExecutorService. Ponemos la partida en
 * IN_PROGRESS a mano y llamamos tick() explícitamente las veces que haga falta.
 * Así cada test es DETERMINISTA: no hay que esperar tiempo real ni existe la
 * posibilidad de que un tick del scheduler se cuele y ensucie el resultado.
 * El único test que sí usa el scheduler es el del final, que precisamente
 * verifica que el loop arranca solo.
 *
 * ZONA NEUTRAL: las unidades se colocan alrededor de y=13, fuera del rango de
 * todas las torres (7.5 tiles). Así los tests de combate miden solo lo que
 * quieren medir, sin que una torre dispare y contamine los números.
 */
class GameEngineServiceTest {

    private static final long TICK_MS = 100L;

    private GameEngineService engine;
    private SimpMessagingTemplate messagingTemplate;
    private MatchEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        eventPublisher = mock(MatchEventPublisher.class);

        engine = new GameEngineService(
                messagingTemplate,
                new GameGrid(),
                new AStarPathfinder(),
                eventPublisher,
                new RewardCalculator());

        // Los @Value los inyecta Spring; aquí los ponemos a mano
        ReflectionTestUtils.setField(engine, "threadPoolSize", 2);
        ReflectionTestUtils.setField(engine, "tickIntervalMs", TICK_MS);
        ReflectionTestUtils.setField(engine, "matchDurationSeconds", 180.0);

        engine.init(); // el @PostConstruct
    }

    @AfterEach
    void tearDown() {
        engine.shutdown(); // no dejar threads colgando entre tests
    }

    // ===== Helpers =====

    private CardSnapshot troop(long id, String name, int cost, int damage, int health,
                               double attackSpeed, double range, String target,
                               boolean aerial, int count) {
        return CardSnapshot.builder()
                .cardId(id).name(name).type("TROOP").elixirCost(cost)
                .damage(damage).health(health).isAerial(aerial)
                .attackSpeed(attackSpeed).movementSpeed(1.0).attackRange(range)
                .target(target).unitCount(count).deploymentType("OWN_SIDE")
                .build();
    }

    /** Mazo de 8 cartas de coste 1: cualquier carta de la mano es jugable. */
    private List<CardSnapshot> cheapDeck() {
        List<CardSnapshot> deck = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            deck.add(troop(i, "Card" + i, 1, 100, 500, 1.0, 1.0, "GROUND", false, 1));
        }
        return deck;
    }

    /** Partida lista para tickear, SIN scheduler. */
    private GameMatch inProgressMatch() {
        GameMatch match = engine.createMatch(1L, cheapDeck(), 2L, cheapDeck());
        match.setStatus(MatchStatus.IN_PROGRESS);
        return match;
    }

    private void tick(GameMatch match, int times) {
        for (int i = 0; i < times; i++) {
            engine.tick(match.getMatchId());
        }
    }

    // ===== Ciclo de vida =====

    @Test
    @DisplayName("createMatch deja la partida en WAITING con ambos jugadores")
    void createsMatchInWaitingState() {
        GameMatch match = engine.createMatch(1L, cheapDeck(), 2L, cheapDeck());

        assertThat(match.getStatus()).isEqualTo(MatchStatus.WAITING);
        assertThat(match.findPlayer(1L).getTeam()).isEqualTo(Team.TEAM_A);
        assertThat(match.findPlayer(2L).getTeam()).isEqualTo(Team.TEAM_B);
        assertThat(engine.getMatchOrThrow(match.getMatchId())).isSameAs(match);
    }

    @Test
    @DisplayName("createMatch registra las 6 torres como obstaculos del pathfinding")
    void registersTowersAsObstacles() {
        GameMatch match = engine.createMatch(1L, cheapDeck(), 2L, cheapDeck());

        // La torre del rey de TEAM_A está en (9.0, 2.5) con radio 2.0
        assertThat(match.getObstacles().isBlocked(new Cell(9, 2))).isTrue();
        // La princesa de TEAM_B en (3.5, 25.5)
        assertThat(match.getObstacles().isBlocked(new Cell(3, 25))).isTrue();
        // Zona vacía
        assertThat(match.getObstacles().isBlocked(new Cell(9, 13))).isFalse();
    }

    @Test
    @DisplayName("Buscar una partida inexistente lanza excepcion")
    void unknownMatchThrows() {
        assertThatThrownBy(() -> engine.getMatchOrThrow("no-existe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("No se puede arrancar dos veces la misma partida")
    void cannotStartTwice() {
        GameMatch match = engine.createMatch(1L, cheapDeck(), 2L, cheapDeck());
        engine.startMatch(match.getMatchId());

        assertThatThrownBy(() -> engine.startMatch(match.getMatchId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("No se aceptan acciones en una partida que no esta en curso")
    void rejectsActionsWhenNotInProgress() {
        GameMatch match = engine.createMatch(1L, cheapDeck(), 2L, cheapDeck());
        // sigue en WAITING

        assertThatThrownBy(() -> engine.submitAction(match.getMatchId(),
                new PlayerAction(1L, 1L, new Position(9, 5), 0L)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ===== El tick =====

    @Test
    @DisplayName("Cada tick regenera elixir y descuenta tiempo")
    void tickRegeneratesElixirAndConsumesTime() {
        GameMatch match = inProgressMatch();
        PlayerState player = match.findPlayer(1L);
        double elixirBefore = player.getElixir();
        double timeBefore = match.getRemainingSeconds();

        tick(match, 1);

        assertThat(player.getElixir()).isGreaterThan(elixirBefore);
        assertThat(match.getRemainingSeconds())
                .isCloseTo(timeBefore - 0.1, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("Una accion valida despliega la unidad y cobra el elixir")
    void validActionDeploysUnit() {
        GameMatch match = inProgressMatch();
        PlayerState player = match.findPlayer(1L);
        CardSnapshot card = player.getHand().get(0);
        double elixirBefore = player.getElixir();

        engine.submitAction(match.getMatchId(),
                new PlayerAction(1L, card.getCardId(), new Position(9, 10), 0L));
        tick(match, 1);

        assertThat(match.getUnits()).hasSize(1);
        assertThat(match.getUnits().values().iterator().next().getCard().getCardId())
                .isEqualTo(card.getCardId());
        // Cobró el elixir (menos lo poco que regeneró en el tick)
        assertThat(player.getElixir()).isLessThan(elixirBefore);
    }

    @Test
    @DisplayName("Una tropa no se despliega en el lado enemigo y el jugador recibe el error")
    void troopCannotBeDeployedOnEnemySide() {
        GameMatch match = inProgressMatch();
        CardSnapshot card = match.findPlayer(1L).getHand().get(0);

        // TEAM_A intenta desplegar en el lado de TEAM_B (y > río)
        engine.submitAction(match.getMatchId(),
                new PlayerAction(1L, card.getCardId(), new Position(9, 25), 0L));
        tick(match, 1);

        assertThat(match.getUnits()).isEmpty();
        // Y se le notificó por su topic de errores
        verify(messagingTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.contains("/errors/1"),
                org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    @DisplayName("Un hechizo SI se lanza en el lado enemigo")
    void spellCanBeCastAnywhere() {
        CardSnapshot fireball = CardSnapshot.builder()
                .cardId(1L).name("Fireball").type("SPELL").elixirCost(1)
                .damage(689).effectRadius(2.5).deploymentType("ANYWHERE")
                .build();
        List<CardSnapshot> deck = new ArrayList<>();
        for (int i = 0; i < 8; i++) deck.add(fireball); // mano llena de hechizos

        GameMatch match = engine.createMatch(1L, deck, 2L, cheapDeck());
        match.setStatus(MatchStatus.IN_PROGRESS);

        TowerState enemyPrincess = match.findPlayer(2L).getTowers().stream()
                .filter(t -> t.getType() == TowerType.PRINCESS_LEFT).findFirst().orElseThrow();
        int hpBefore = enemyPrincess.getCurrentHealth();

        // Lanzado sobre la torre enemiga (lado contrario del tablero)
        engine.submitAction(match.getMatchId(),
                new PlayerAction(1L, 1L, enemyPrincess.getPosition(), 0L));
        tick(match, 1);

        assertThat(enemyPrincess.getCurrentHealth()).isLessThan(hpBefore);
    }

    @Test
    @DisplayName("Una carta con unitCount>1 despliega varias unidades separadas")
    void multiUnitCardDeploysAllUnits() {
        CardSnapshot skeletons = troop(1L, "Skeletons", 1, 81, 81, 1.0, 0.8, "GROUND", false, 3);
        List<CardSnapshot> deck = new ArrayList<>();
        for (int i = 0; i < 8; i++) deck.add(skeletons);

        GameMatch match = engine.createMatch(1L, deck, 2L, cheapDeck());
        match.setStatus(MatchStatus.IN_PROGRESS);

        engine.submitAction(match.getMatchId(), new PlayerAction(1L, 1L, new Position(9, 10), 0L));
        tick(match, 1);

        assertThat(match.getUnits()).hasSize(3);
        // No nacen todas en el mismo punto exacto
        assertThat(match.getUnits().values())
                .extracting(u -> u.getPosition().x())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Las unidades muertas se remueven del tablero")
    void deadUnitsAreRemoved() {
        GameMatch match = inProgressMatch();
        DeployedUnit doomed = new DeployedUnit(
                troop(1L, "X", 1, 10, 100, 1.0, 1.0, "GROUND", false, 1),
                Team.TEAM_A, new Position(9, 13));
        match.getUnits().put(doomed.getInstanceId(), doomed);
        doomed.applyDamage(999_999);

        tick(match, 1);

        assertThat(match.getUnits()).doesNotContainKey(doomed.getInstanceId());
    }

    // ===== Targeting aire / tierra =====

    @Test
    @DisplayName("Una tropa GROUND ignora a las unidades aereas")
    void groundUnitCannotAttackAerial() {
        GameMatch match = inProgressMatch();

        CardSnapshot knightCard = troop(1L, "Knight", 3, 202, 1766, 1.2, 0.8, "GROUND", false, 1);
        CardSnapshot minionCard = troop(2L, "Minion", 3, 84, 190, 1.0, 2.0, "AIR_AND_GROUND", true, 1);

        DeployedUnit knight = new DeployedUnit(knightCard, Team.TEAM_A, new Position(9, 13));
        DeployedUnit minion = new DeployedUnit(minionCard, Team.TEAM_B, new Position(9, 13.5));
        match.getUnits().put(knight.getInstanceId(), knight);
        match.getUnits().put(minion.getInstanceId(), minion);

        tick(match, 1);

        // El Knight no puede tocarlo: el Minion sale intacto
        assertThat(minion.getCurrentHealth()).isEqualTo(minionCard.getHealth());
        // Pero el Minion sí le pega al Knight
        assertThat(knight.getCurrentHealth()).isLessThan(knightCard.getHealth());
    }

    @Test
    @DisplayName("Una tropa AIR_AND_GROUND si ataca a las aereas")
    void airAndGroundUnitCanAttackAerial() {
        GameMatch match = inProgressMatch();

        CardSnapshot archerCard = troop(1L, "Archer", 3, 118, 304, 1.2, 5.0, "AIR_AND_GROUND", false, 1);
        CardSnapshot minionCard = troop(2L, "Minion", 3, 84, 190, 1.0, 2.0, "AIR_AND_GROUND", true, 1);

        DeployedUnit archer = new DeployedUnit(archerCard, Team.TEAM_A, new Position(9, 13));
        DeployedUnit minion = new DeployedUnit(minionCard, Team.TEAM_B, new Position(9, 13.5));
        match.getUnits().put(archer.getInstanceId(), archer);
        match.getUnits().put(minion.getInstanceId(), minion);

        tick(match, 1);

        assertThat(minion.getCurrentHealth()).isLessThan(minionCard.getHealth());
    }

    @Test
    @DisplayName("Una tropa BUILDINGS_ONLY ataca edificios pero ignora tropas")
    void buildingsOnlyUnitAttacksBuildingsAndIgnoresTroops() {
        GameMatch match = inProgressMatch();

        CardSnapshot giantCard = troop(1L, "Giant", 5, 254, 4091, 1.5, 0.8, "BUILDINGS_ONLY", false, 1);
        CardSnapshot knightCard = troop(2L, "Knight", 3, 202, 1766, 1.2, 0.8, "GROUND", false, 1);
        CardSnapshot cannonCard = CardSnapshot.builder()
                .cardId(3L).name("Cannon").type("BUILDING").elixirCost(3)
                .damage(212).health(742).isAerial(false)
                .attackSpeed(0.8).movementSpeed(0.0).attackRange(5.5)
                .target("GROUND").unitCount(1).deploymentType("OWN_SIDE")
                .build();

        DeployedUnit giant = new DeployedUnit(giantCard, Team.TEAM_A, new Position(9, 13));
        DeployedUnit knight = new DeployedUnit(knightCard, Team.TEAM_B, new Position(9, 13.4));
        DeployedUnit cannon = new DeployedUnit(cannonCard, Team.TEAM_B, new Position(9, 13.6));
        match.getUnits().put(giant.getInstanceId(), giant);
        match.getUnits().put(knight.getInstanceId(), knight);
        match.getUnits().put(cannon.getInstanceId(), cannon);

        tick(match, 1);

        // Aunque el Knight está MÁS CERCA, el Giant va por el edificio
        assertThat(cannon.getCurrentHealth()).isLessThan(cannonCard.getHealth());
        assertThat(knight.getCurrentHealth()).isEqualTo(knightCard.getHealth());
    }

    // ===== Fin de partida =====

    @Test
    @DisplayName("Tumbar la torre del rey termina la partida al instante")
    void kingTowerDownEndsMatchImmediately() {
        GameMatch match = inProgressMatch();
        TowerState kingB = match.findPlayer(2L).getKingTower();
        kingB.applyDamage(kingB.getMaxHealth());

        tick(match, 1);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.FINISHED);
        assertThat(match.getWinner()).isEqualTo(Team.TEAM_A);
        // El tiempo sobraba: no fue por timeout
        assertThat(match.getRemainingSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Al agotarse el tiempo gana quien destruyo mas torres")
    void timeoutWinnerIsWhoDestroyedMoreTowers() {
        GameMatch match = inProgressMatch();
        match.setRemainingSeconds(0.05); // el próximo tick lo agota

        TowerState princessB = match.findPlayer(2L).getTowers().stream()
                .filter(t -> t.getType() == TowerType.PRINCESS_LEFT).findFirst().orElseThrow();
        princessB.applyDamage(princessB.getMaxHealth()); // TEAM_A le tumbó una

        tick(match, 1);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.FINISHED);
        assertThat(match.getWinner()).isEqualTo(Team.TEAM_A);
    }

    @Test
    @DisplayName("Al agotarse el tiempo con torres iguales, es empate")
    void timeoutWithEqualTowersIsDraw() {
        GameMatch match = inProgressMatch();
        match.setRemainingSeconds(0.05);

        tick(match, 1);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.FINISHED);
        assertThat(match.getWinner()).isNull();
    }

    @Test
    @DisplayName("Una partida terminada deja de tickear")
    void finishedMatchStopsTicking() {
        GameMatch match = inProgressMatch();
        TowerState kingB = match.findPlayer(2L).getKingTower();
        kingB.applyDamage(kingB.getMaxHealth());
        tick(match, 1);

        double timeAtFinish = match.getRemainingSeconds();
        tick(match, 10); // ticks de más

        assertThat(match.getRemainingSeconds()).isEqualTo(timeAtFinish);
    }

    // ===== [4B] Eventos =====

    @Test
    @DisplayName("[4B] Al terminar se publica el MatchFinishedEvent con el resultado")
    void publishesEventOnFinish() {
        GameMatch match = inProgressMatch();
        TowerState kingB = match.findPlayer(2L).getKingTower();
        kingB.applyDamage(kingB.getMaxHealth());

        tick(match, 1);

        ArgumentCaptor<MatchFinishedEvent> captor =
                ArgumentCaptor.forClass(MatchFinishedEvent.class);
        verify(eventPublisher).publishMatchFinished(captor.capture());

        MatchFinishedEvent event = captor.getValue();
        assertThat(event.matchId()).isEqualTo(match.getMatchId());
        assertThat(event.winnerTeam()).isEqualTo("TEAM_A");
        assertThat(event.players()).hasSize(2);
    }

    @Test
    @DisplayName("[4B] El evento trae trofeos simetricos: lo que uno gana, el otro lo pierde")
    void eventCarriesSymmetricTrophies() {
        GameMatch match = inProgressMatch();
        TowerState kingB = match.findPlayer(2L).getKingTower();
        kingB.applyDamage(kingB.getMaxHealth());

        tick(match, 1);

        ArgumentCaptor<MatchFinishedEvent> captor =
                ArgumentCaptor.forClass(MatchFinishedEvent.class);
        verify(eventPublisher).publishMatchFinished(captor.capture());

        MatchFinishedEvent.PlayerResult winner = captor.getValue().players().stream()
                .filter(MatchFinishedEvent.PlayerResult::won).findFirst().orElseThrow();
        MatchFinishedEvent.PlayerResult loser = captor.getValue().players().stream()
                .filter(p -> !p.won()).findFirst().orElseThrow();

        assertThat(winner.userId()).isEqualTo(1L);
        assertThat(loser.userId()).isEqualTo(2L);
        assertThat(winner.trophyChange()).isEqualTo(-loser.trophyChange());
        // El rey cayó, pero las princesas siguen en pie: 1 corona, no es three-crown
        assertThat(winner.crownsEarned()).isEqualTo(1);
        assertThat(winner.threeCrownWin()).isFalse();
    }

    // ===== Snapshot =====

    @Test
    @DisplayName("El snapshot refleja el estado de la partida")
    void snapshotReflectsState() {
        GameMatch match = inProgressMatch();

        MatchSnapshotDTO snap = engine.buildSnapshot(match.getMatchId());

        assertThat(snap.matchId()).isEqualTo(match.getMatchId());
        assertThat(snap.status()).isEqualTo("IN_PROGRESS");
        assertThat(snap.players()).hasSize(2);
        assertThat(snap.towers()).hasSize(6);
        assertThat(snap.units()).isEmpty();
        assertThat(snap.winner()).isNull();
    }

    @Test
    @DisplayName("El snapshot nunca reporta tiempo negativo")
    void snapshotClampsNegativeTime() {
        GameMatch match = inProgressMatch();
        match.setRemainingSeconds(-5.0);

        assertThat(engine.buildSnapshot(match.getMatchId()).remainingSeconds()).isZero();
    }

    // ===== Aislamiento entre partidas =====

    @Test
    @DisplayName("El tick de una partida no toca a las demas")
    void ticksAreIsolatedBetweenMatches() {
        GameMatch m1 = inProgressMatch();
        GameMatch m2 = engine.createMatch(3L, cheapDeck(), 4L, cheapDeck());
        m2.setStatus(MatchStatus.IN_PROGRESS);

        double m2TimeBefore = m2.getRemainingSeconds();
        double m2ElixirBefore = m2.findPlayer(3L).getElixir();

        tick(m1, 5);

        assertThat(m2.getRemainingSeconds()).isEqualTo(m2TimeBefore);
        assertThat(m2.findPlayer(3L).getElixir()).isEqualTo(m2ElixirBefore);
    }

    // ===== El loop real =====

    /**
     * El único test que usa el scheduler de verdad: verifica que startMatch
     * arranca el loop y que la partida termina sola al agotarse el tiempo.
     * Duración de 0.5s para no esperar 3 minutos.
     */
    @Test
    @DisplayName("El game loop corre solo y termina la partida al agotarse el tiempo")
    void gameLoopRunsAndFinishesMatchOnTimeout() throws InterruptedException {
        // La duración se lee al CREAR la partida: hay que fijarla antes
        ReflectionTestUtils.setField(engine, "matchDurationSeconds", 0.5);

        GameMatch match = engine.createMatch(1L, cheapDeck(), 2L, cheapDeck());
        engine.startMatch(match.getMatchId());

        long deadline = System.currentTimeMillis() + 5000;
        while (match.getStatus() != MatchStatus.FINISHED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(match.getStatus())
                .as("la partida deberia haber terminado sola")
                .isEqualTo(MatchStatus.FINISHED);
        assertThat(match.getRemainingSeconds()).isLessThanOrEqualTo(0);
    }

    /**
     * Varias partidas simultáneas sobre el pool compartido. Verifica que el
     * diseño de "N threads atienden M partidas" no cruza estados: cada partida
     * termina con su propio resultado.
     */
    @Test
    @DisplayName("Varias partidas corren en paralelo sobre el pool sin interferirse")
    void multipleMatchesRunConcurrentlyOnSharedPool() throws InterruptedException {
        ReflectionTestUtils.setField(engine, "matchDurationSeconds", 0.5);

        List<GameMatch> matches = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            GameMatch m = engine.createMatch(i * 2 + 1, cheapDeck(), i * 2 + 2, cheapDeck());
            matches.add(m);
            engine.startMatch(m.getMatchId());
        }

        long deadline = System.currentTimeMillis() + 10_000;
        while (matches.stream().anyMatch(m -> m.getStatus() != MatchStatus.FINISHED)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        // Las 10 terminaron, pese a que el pool solo tiene 2 threads
        assertThat(matches).allMatch(m -> m.getStatus() == MatchStatus.FINISHED);
        // Y cada una conserva su identidad
        assertThat(matches).extracting(GameMatch::getMatchId).doesNotHaveDuplicates();
    }
}