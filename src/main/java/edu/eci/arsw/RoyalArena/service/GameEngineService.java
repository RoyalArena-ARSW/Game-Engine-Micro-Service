package edu.eci.arsw.RoyalArena.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import edu.eci.arsw.RoyalArena.events.MatchEventPublisher;
import edu.eci.arsw.RoyalArena.events.MatchFinishedEvent;
import edu.eci.arsw.RoyalArena.dto.ActionErrorDTO;
import edu.eci.arsw.RoyalArena.dto.MatchSnapshotDTO;
import edu.eci.arsw.RoyalArena.dto.PlayerSnapshotDTO;
import edu.eci.arsw.RoyalArena.dto.TowerSnapshotDTO;
import edu.eci.arsw.RoyalArena.dto.UnitSnapshotDTO;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;
import edu.eci.arsw.RoyalArena.model.Cell;
import edu.eci.arsw.RoyalArena.model.DeployedUnit;
import edu.eci.arsw.RoyalArena.model.GameConstants;
import edu.eci.arsw.RoyalArena.model.GameMatch;
import edu.eci.arsw.RoyalArena.model.PlayerState;
import edu.eci.arsw.RoyalArena.model.TowerState;
import edu.eci.arsw.RoyalArena.model.enums.MatchStatus;
import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.TowerType;
import edu.eci.arsw.RoyalArena.model.enums.UnitState;
import edu.eci.arsw.RoyalArena.model.records.PlayerAction;
import edu.eci.arsw.RoyalArena.model.records.Position;
import edu.eci.arsw.RoyalArena.pathfinding.AStarPathfinder;
import edu.eci.arsw.RoyalArena.pathfinding.GameGrid;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Motor de partidas. Mantiene todas las partidas activas en memoria y
 * ejecuta el game loop de cada una sobre un pool de threads compartido.
 *
 * ============ DISEÑO DE CONCURRENCIA ============
 *
 * 1. activeMatches es un ConcurrentHashMap: múltiples threads (REST,
 *    WebSocket, scheduler) consultan y agregan partidas sin bloquearse.
 *
 * 2. Patrón SINGLE-WRITER por partida: el estado de cada GameMatch lo
 *    escribe ÚNICAMENTE su tick. Los jugadores no mutan nada: encolan
 *    PlayerActions en una ConcurrentLinkedQueue y el tick las drena.
 *
 * 3. ScheduledExecutorService COMPARTIDO: N threads atienden M partidas
 *    (N << M). scheduleAtFixedRate garantiza que los ticks de UNA misma
 *    partida nunca se solapen; los de partidas distintas sí corren en
 *    paralelo.
 *
 * 4. Los lectores (snapshots) leen campos volatile y colecciones
 *    concurrentes: pueden ver un estado "a mitad de tick" pero nunca
 *    corrupto.
 */
@Slf4j
@Service
public class GameEngineService {

    /** Umbral (en tiles) para considerar "alcanzado" un waypoint de la ruta. */
    private static final double WAYPOINT_REACHED_THRESHOLD = 0.5;

    private final Map<String, GameMatch> activeMatches = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> matchTasks = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;
    private final GameGrid gameGrid;
    private final AStarPathfinder pathfinder;

    private ScheduledExecutorService scheduler;

    private final MatchEventPublisher eventPublisher;
    private final RewardCalculator rewardCalculator;

    @Value("${game.engine.thread-pool-size:4}")
    private int threadPoolSize;

    @Value("${game.tick.interval-ms:100}")
    private long tickIntervalMs;

    @Value("${game.match.duration-seconds:180}")
    private double matchDurationSeconds;

    public GameEngineService(SimpMessagingTemplate messagingTemplate,
                             GameGrid gameGrid,
                             AStarPathfinder pathfinder,
                             MatchEventPublisher eventPublisher,
                             RewardCalculator rewardCalculator) {
        this.messagingTemplate = messagingTemplate;
        this.gameGrid = gameGrid;
        this.pathfinder = pathfinder;
        this.eventPublisher = eventPublisher;
        this.rewardCalculator = rewardCalculator;
    }

    @PostConstruct
    public void init() {
        this.scheduler = Executors.newScheduledThreadPool(threadPoolSize);
        log.info("GameEngine initialized: pool={} threads, tick={}ms, duration={}s",
                threadPoolSize, tickIntervalMs, matchDurationSeconds);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ==================== Ciclo de vida de partidas ====================

    /**
     * Crea una partida con los mazos ya resueltos (TestDataFactory ahora,
     * Deck-and-Cards en Fase 4).
     */
    public GameMatch createMatch(Long userA, List<CardSnapshot> deckA,
                                 Long userB, List<CardSnapshot> deckB) {
        PlayerState playerA = new PlayerState(userA, Team.TEAM_A, deckA);
        PlayerState playerB = new PlayerState(userB, Team.TEAM_B, deckB);
        GameMatch match = new GameMatch(playerA, playerB, matchDurationSeconds);

        registerTowerObstacles(match);

        activeMatches.put(match.getMatchId(), match);
        log.info("Match {} created: {} (TEAM_A) vs {} (TEAM_B)", match.getMatchId(), userA, userB);
        return match;
    }

    /**
     * Arranca el game loop de la partida. A partir de aquí, un thread del
     * pool ejecuta tick() cada tickIntervalMs para esta partida.
     */
    public void startMatch(String matchId) {
        GameMatch match = getMatchOrThrow(matchId);
        if (match.getStatus() != MatchStatus.WAITING) {
            throw new IllegalStateException("Match " + matchId + " is not in WAITING state");
        }
        match.setStatus(MatchStatus.IN_PROGRESS);
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> safeTick(matchId), 0, tickIntervalMs, TimeUnit.MILLISECONDS);
        matchTasks.put(matchId, task);
        log.info("Match {} started", matchId);
    }

    /**
     * Encola la acción de un jugador. Llamable desde cualquier thread.
     * NO muta el estado: el tick la procesará.
     */
    public void submitAction(String matchId, PlayerAction action) {
        GameMatch match = getMatchOrThrow(matchId);
        if (!match.isInProgress()) {
            throw new IllegalStateException("Match " + matchId + " is not in progress");
        }
        match.enqueueAction(action);
    }

    public GameMatch getMatchOrThrow(String matchId) {
        GameMatch match = activeMatches.get(matchId);
        if (match == null) {
            throw new IllegalArgumentException("Match not found: " + matchId);
        }
        return match;
    }

    // ==================== El game loop ====================

    /**
     * Envoltorio del tick con manejo de errores: si un tick lanza una excepción
     * no capturada, scheduleAtFixedRate CANCELA silenciosamente las siguientes
     * ejecuciones y la partida se congelaría sin logs.
     */
    private void safeTick(String matchId) {
        try {
            tick(matchId);
        } catch (Exception e) {
            log.error("Tick failed for match {}: {}", matchId, e.getMessage(), e);
        }
    }

    private void tick(String matchId) {
        GameMatch match = activeMatches.get(matchId);
        if (match == null || !match.isInProgress()) {
            return;
        }

        double deltaSeconds = tickIntervalMs / 1000.0;

        // 1. Acciones encoladas de los jugadores
        processPendingActions(match);

        // 2. Elixir
        match.getPlayersByTeam().values().stream()
                .flatMap(List::stream)
                .forEach(p -> p.regenerateElixir(deltaSeconds));

        // 3. Unidades: targeting, movimiento (A*), combate, colisiones
        updateUnits(match, deltaSeconds);

        // 4. Torres atacan
        updateTowers(match);

        // 5. Liberar celdas de torres destruidas
        releaseDestroyedTowers(match);

        // 6. Remover unidades muertas (liberando obstáculos si eran edificios)
        removeDeadUnits(match);

        // 7. Tiempo y fin de partida
        match.setRemainingSeconds(match.getRemainingSeconds() - deltaSeconds);
        checkVictoryConditions(match);

        // 8. Emitir estado a los clientes
        broadcastState(match);
    }

    // ==================== Acciones de jugadores ====================

    private void processPendingActions(GameMatch match) {
        PlayerAction polled;
        while ((polled = match.getPendingActions().poll()) != null) {
            final PlayerAction action = polled;

            PlayerState player = match.findPlayer(action.playerId());
            if (player == null) {
                continue;
            }

            CardSnapshot card = player.findCardInDeck(action.cardId());
            if (card == null) {
                sendActionError(match, action.playerId(), action.cardId(),
                        "La carta no pertenece a tu mazo");
                continue;
            }

            boolean inHand = player.getHand().stream()
                    .anyMatch(c -> c.getCardId().equals(action.cardId()));
            if (!inHand) {
                sendActionError(match, action.playerId(), action.cardId(),
                        "La carta no está en tu mano en este momento");
                continue;
            }

            if (!isValidDeployment(player.getTeam(), action.position(), card)) {
                sendActionError(match, action.playerId(), action.cardId(),
                        "No puedes desplegar esa carta en esa posición");
                continue;
            }

            if (player.getElixir() < card.getElixirCost()) {
                sendActionError(match, action.playerId(), action.cardId(),
                        "Elixir insuficiente (tienes " + String.format("%.1f", player.getElixir())
                                + ", necesitas " + card.getElixirCost() + ")");
                continue;
            }

            if (!player.tryPlayCard(action.cardId())) {
                sendActionError(match, action.playerId(), action.cardId(),
                        "No se pudo jugar la carta");
                continue;
            }

            deployCard(match, player, card, action.position());
        }
    }

    private void sendActionError(GameMatch match, Long playerId, Long cardId, String reason) {
        log.debug("Action rejected for player {} card {}: {}", playerId, cardId, reason);
        messagingTemplate.convertAndSend(
                "/topic/match/" + match.getMatchId() + "/errors/" + playerId,
                new ActionErrorDTO(playerId, cardId, reason));
    }

    /**
     * - Fuera del tablero: nunca.
     * - Cartas ANYWHERE (hechizos): en cualquier parte.
     * - Cartas OWN_SIDE (tropas, edificios): solo en la mitad propia.
     */
    private boolean isValidDeployment(Team team, Position pos, CardSnapshot card) {
        if (pos.x() < 0 || pos.x() > GameConstants.BOARD_WIDTH
                || pos.y() < 0 || pos.y() > GameConstants.BOARD_HEIGHT) {
            return false;
        }
        if ("ANYWHERE".equals(card.getDeploymentType())) {
            return true;
        }
        if (team == Team.TEAM_A) {
            return pos.y() < GameConstants.RIVER_Y_MIN;
        }
        return pos.y() > GameConstants.RIVER_Y_MAX;
    }

    private void deployCard(GameMatch match, PlayerState player, CardSnapshot card, Position pos) {
        if ("SPELL".equals(card.getType())) {
            castSpell(match, player, card, pos);
            return;
        }
        int count = card.getUnitCount() != null ? card.getUnitCount() : 1;
        for (int i = 0; i < count; i++) {
            double offset = (i - (count - 1) / 2.0) * 0.7;
            Position spawn = new Position(pos.x() + offset, pos.y());
            DeployedUnit unit = new DeployedUnit(card, player.getTeam(), spawn);
            match.getUnits().put(unit.getInstanceId(), unit);

            // Los edificios son estáticos y bloquean el paso
            if ("BUILDING".equals(card.getType())) {
                match.getObstacles().addObstacle(
                        unitObstacleId(unit), spawn, GameConstants.BUILDING_RADIUS);
            }
        }
        log.debug("Player {} deployed {} x{} at {}", player.getUserId(), card.getName(), count, pos);
    }

    /**
     * Hechizo simplificado: daño instantáneo a unidades y torres enemigas
     * dentro del radio.
     */
    private void castSpell(GameMatch match, PlayerState caster, CardSnapshot spell, Position pos) {
        double radius = spell.getEffectRadius() != null ? spell.getEffectRadius() : 2.0;
        int damage = spell.getDamage() != null ? spell.getDamage() : 0;
        Team enemyTeam = caster.getTeam().opposite();

        match.getUnits().values().stream()
                .filter(u -> u.getTeam() == enemyTeam)
                .filter(u -> u.getPosition().distanceTo(pos) <= radius)
                .forEach(u -> u.applyDamage(damage));

        match.getPlayersOf(enemyTeam).forEach(enemy ->
                enemy.getTowers().stream()
                        .filter(t -> !t.isDestroyed())
                        .filter(t -> t.getPosition().distanceTo(pos) <= radius)
                        .forEach(t -> t.applyDamage(damage)));

        log.debug("Spell {} cast at {} dealing {} in radius {}", spell.getName(), pos, damage, radius);
    }

    // ==================== Unidades: targeting, A*, combate ====================

/**
     * Por cada unidad viva: elegir objetivo válido (respetando aire/tierra),
     * atacar si está en rango, o moverse. Las unidades terrestres siguen la
     * ruta A*; las aéreas vuelan en línea recta ignorando el terreno.
     */
    private void updateUnits(GameMatch match, double deltaSeconds) {
        for (DeployedUnit unit : match.getUnits().values()) {
            if (unit.isDead()) continue;

            boolean isBuilding = "BUILDING".equals(unit.getCard().getType());
            boolean isAerial = isAerialUnit(unit);

            unit.reduceCooldown(tickIntervalMs);
            final Position unitPos = unit.getPosition();
            Team enemyTeam = unit.getTeam().opposite();

            // Enemigo más cercano que ESTA unidad pueda atacar
            DeployedUnit nearestEnemyUnit = match.getUnits().values().stream()
                    .filter(u -> u.getTeam() == enemyTeam && !u.isDead())
                    .filter(u -> canTarget(unit, u))
                    .min(Comparator.comparingDouble(u -> u.getPosition().distanceTo(unitPos)))
                    .orElse(null);

            // Las torres son objetivo válido para todos
            TowerState nearestTower = match.getPlayersOf(enemyTeam).stream()
                    .flatMap(p -> p.getTowers().stream())
                    .filter(t -> !t.isDestroyed())
                    .min(Comparator.comparingDouble(t -> t.getPosition().distanceTo(unitPos)))
                    .orElse(null);

            // Distancias EFECTIVAS: al borde del objetivo, no a su centro
            double distToUnit = nearestEnemyUnit != null
                    ? unitPos.distanceTo(nearestEnemyUnit.getPosition()) - GameConstants.UNIT_RADIUS
                    : Double.MAX_VALUE;
            double distToTower = nearestTower != null
                    ? unitPos.distanceTo(nearestTower.getPosition()) - towerRadius(nearestTower)
                    : Double.MAX_VALUE;

            final int unitDamage = damageOf(unit);
            Position targetPos;
            String targetId;
            Runnable attackAction;
            double effectiveDistance;

            if (distToUnit <= distToTower && nearestEnemyUnit != null) {
                final DeployedUnit target = nearestEnemyUnit;
                targetPos = target.getPosition();
                targetId = unitObstacleId(target);
                attackAction = () -> target.applyDamage(unitDamage);
                effectiveDistance = distToUnit;
            } else if (nearestTower != null) {
                final TowerState target = nearestTower;
                targetPos = target.getPosition();
                targetId = towerId(target);
                attackAction = () -> target.applyDamage(unitDamage);
                effectiveDistance = distToTower;
            } else {
                continue;
            }

            double range = unit.getCard().getAttackRange() != null
                    ? Math.max(unit.getCard().getAttackRange(), 0.8) : 0.8;

            if (effectiveDistance <= range) {
                unit.setState(UnitState.ATTACKING);
                if (unit.canAttack()) {
                    attackAction.run();
                    double cooldown = unit.getCard().getAttackSpeed() != null
                            ? unit.getCard().getAttackSpeed() * 1000 : 1000;
                    unit.setAttackCooldownMs(cooldown);
                }
            } else if (isBuilding) {
                // Edificio sin nada en rango: se queda quieto
                unit.setState(UnitState.MOVING);
            } else if (isAerial) {
                // VUELA: ignora el río, las torres y todo el terreno.
                // Sin A*: línea recta al objetivo. Más barato y más correcto.
                unit.setState(UnitState.MOVING);
                unit.moveTowards(targetPos, deltaSeconds);
            } else {
                // Terrestre: sigue la ruta A*
                unit.setState(UnitState.MOVING);

                int currentVersion = match.getObstacles().getVersion();
                boolean targetChanged = !targetId.equals(unit.getPathTargetId());
                boolean obstaclesChanged = currentVersion != unit.getPathObstaclesVersion();

                if (targetChanged || obstaclesChanged) {
                    Cell start = positionToCell(unitPos);
                    Cell goal = positionToCell(targetPos);
                    List<Cell> cells = pathfinder.findPath(
                            gameGrid, match.getObstacles(), start, goal, targetId);
                    List<Position> waypoints = new ArrayList<>();
                    for (Cell c : cells) {
                        waypoints.add(cellCenter(c));
                    }
                    unit.setPath(waypoints, targetId, currentVersion);
                }

                followPath(unit, deltaSeconds, targetPos);
            }
        }

        resolveUnitCollisions(match);
    }

    /**
     * Resuelve solapamientos entre unidades (colisión simplificada).
     *
     * No es física completa: es un "steering behavior" de separación. Si dos
     * unidades se solapan, se empujan a lo largo del eje que las une, cada una
     * la mitad de la penetración, amortiguado por SEPARATION_STRENGTH. Los
     * edificios no ceden (son estáticos): solo se aparta la otra.
     *
     * O(n²), aceptable con las decenas de unidades de una partida.
     */
    private void resolveUnitCollisions(GameMatch match) {
        List<DeployedUnit> units = new ArrayList<>(match.getUnits().values());
        
        for (int i = 0; i < units.size(); i++) {
            for (int j = i + 1; j < units.size(); j++) {
                DeployedUnit a = units.get(i);
                DeployedUnit b = units.get(j);
                if (a.isDead() || b.isDead()) continue;

                // Solo colisionan unidades del MISMO plano: las aéreas vuelan
                // por encima de las terrestres (y de los edificios).
                if (isAerialUnit(a) != isAerialUnit(b)) continue;

                boolean aStatic = "BUILDING".equals(a.getCard().getType());
                boolean bStatic = "BUILDING".equals(b.getCard().getType());
                if (aStatic && bStatic) continue;

                double minDistance = GameConstants.UNIT_RADIUS * 2;
                double dx = b.getPosition().x() - a.getPosition().x();
                double dy = b.getPosition().y() - a.getPosition().y();
                double distance = Math.sqrt(dx * dx + dy * dy);

                if (distance >= minDistance) continue;

                // Caso degenerado: exactamente encima
                if (distance < 0.0001) {
                    dx = 0.01;
                    dy = 0.0;
                    distance = 0.01;
                }

                double penetration = minDistance - distance;
                double pushX = (dx / distance) * penetration * GameConstants.SEPARATION_STRENGTH;
                double pushY = (dy / distance) * penetration * GameConstants.SEPARATION_STRENGTH;

                if (aStatic) {
                    b.setPosition(clampToBoard(new Position(
                            b.getPosition().x() + pushX, b.getPosition().y() + pushY)));
                } else if (bStatic) {
                    a.setPosition(clampToBoard(new Position(
                            a.getPosition().x() - pushX, a.getPosition().y() - pushY)));
                } else {
                    a.setPosition(clampToBoard(new Position(
                            a.getPosition().x() - pushX / 2, a.getPosition().y() - pushY / 2)));
                    b.setPosition(clampToBoard(new Position(
                            b.getPosition().x() + pushX / 2, b.getPosition().y() + pushY / 2)));
                }
            }
        }
    }

    /**
     * Mueve la unidad hacia el siguiente waypoint de su ruta A*. Si no hay ruta
     * o se agotó, se aproxima en línea recta al objetivo (red de seguridad).
     */
    private void followPath(DeployedUnit unit, double deltaSeconds, Position finalTarget) {
        List<Position> path = unit.getPath();
        if (path == null || path.isEmpty()) {
            unit.moveTowards(finalTarget, deltaSeconds);
            return;
        }

        int idx = unit.getPathIndex();
        while (idx < path.size()
                && unit.getPosition().distanceTo(path.get(idx)) < WAYPOINT_REACHED_THRESHOLD) {
            idx++;
        }
        unit.setPathIndex(idx);

        if (idx >= path.size()) {
            unit.moveTowards(finalTarget, deltaSeconds);
            return;
        }
        unit.moveTowards(path.get(idx), deltaSeconds);
    }

    // ==================== Torres ====================

    /**
     * Cada torre viva ataca a la unidad enemiga más cercana en su rango.
     */
    private void updateTowers(GameMatch match) {
        for (Team team : Team.values()) {
            Team enemyTeam = team.opposite();
            for (PlayerState player : match.getPlayersOf(team)) {
                for (TowerState tower : player.getTowers()) {
                    if (tower.isDestroyed()) continue;
                    tower.reduceCooldown(tickIntervalMs);
                    if (!tower.canAttack()) continue;

                    match.getUnits().values().stream()
                            .filter(u -> u.getTeam() == enemyTeam && !u.isDead())
                            .filter(u -> u.getPosition().distanceTo(tower.getPosition())
                                    <= GameConstants.TOWER_ATTACK_RANGE)
                            .min(Comparator.comparingDouble(u ->
                                    u.getPosition().distanceTo(tower.getPosition())))
                            .ifPresent(target -> {
                                target.applyDamage(GameConstants.TOWER_DAMAGE);
                                tower.setAttackCooldownMs(
                                        GameConstants.TOWER_ATTACK_SPEED_SECONDS * 1000);
                            });
                }
            }
        }
    }

    /** Marca las celdas que ocupan las torres para que el pathfinding las rodee. */
    private void registerTowerObstacles(GameMatch match) {
        for (Team team : Team.values()) {
            for (PlayerState player : match.getPlayersOf(team)) {
                for (TowerState tower : player.getTowers()) {
                    match.getObstacles().addObstacle(
                            towerId(tower), tower.getPosition(), towerRadius(tower));
                }
            }
        }
    }

    /**
     * Libera las celdas de las torres destruidas. Al hacerlo, la versión de
     * obstáculos cambia y las unidades recalculan sus rutas: el terreno se abre.
     */
    private void releaseDestroyedTowers(GameMatch match) {
        for (Team team : Team.values()) {
            for (PlayerState player : match.getPlayersOf(team)) {
                for (TowerState tower : player.getTowers()) {
                    if (tower.isDestroyed()) {
                        // removeObstacle es idempotente
                        match.getObstacles().removeObstacle(towerId(tower));
                    }
                }
            }
        }
    }

    /**
     * Remueve las unidades muertas. Si eran edificios, libera sus celdas.
     */
    private void removeDeadUnits(GameMatch match) {
        match.getUnits().values().removeIf(unit -> {
            if (!unit.isDead()) {
                return false;
            }
            if ("BUILDING".equals(unit.getCard().getType())) {
                match.getObstacles().removeObstacle(unitObstacleId(unit));
            }
            return true;
        });
    }

    // ==================== Fin de partida ====================

    /**
     * Torre del rey destruida (victoria instantánea) o tiempo agotado (gana
     * quien haya destruido más torres; empate posible).
     */
    private void checkVictoryConditions(GameMatch match) {
        boolean kingADown = match.getPlayersOf(Team.TEAM_A).stream()
                .anyMatch(PlayerState::isKingTowerDestroyed);
        boolean kingBDown = match.getPlayersOf(Team.TEAM_B).stream()
                .anyMatch(PlayerState::isKingTowerDestroyed);

        if (kingADown) {
            finishMatch(match, Team.TEAM_B);
        } else if (kingBDown) {
            finishMatch(match, Team.TEAM_A);
        } else if (match.getRemainingSeconds() <= 0) {
            long destroyedOfA = match.getPlayersOf(Team.TEAM_A).stream()
                    .mapToLong(PlayerState::countDestroyedTowers).sum();
            long destroyedOfB = match.getPlayersOf(Team.TEAM_B).stream()
                    .mapToLong(PlayerState::countDestroyedTowers).sum();
            // destroyedOfA = torres de A destruidas → puntos para B
            Team winner = destroyedOfA > destroyedOfB ? Team.TEAM_B
                    : destroyedOfB > destroyedOfA ? Team.TEAM_A : null;
            finishMatch(match, winner);
        }
    }

    private void finishMatch(GameMatch match, Team winner) {
        match.setStatus(MatchStatus.FINISHED);
        match.setWinner(winner);

        ScheduledFuture<?> task = matchTasks.remove(match.getMatchId());
        if (task != null) {
            task.cancel(false);
        }
        log.info("Match {} finished. Winner: {}", match.getMatchId(),
                winner != null ? winner : "DRAW");

        broadcastState(match);

         eventPublisher.publishMatchFinished(buildMatchFinishedEvent(match, winner));
    }

    /**
     * Arma el evento de fin de partida con el resultado de cada jugador.
     *
     * Las coronas: las que un jugador GANA son las torres que perdió su rival.
     * Un three-crown win es ganar habiendo destruido las 3.
     */
    private MatchFinishedEvent buildMatchFinishedEvent(GameMatch match, Team winner) {
        List<MatchFinishedEvent.PlayerResult> results = new ArrayList<>();
        boolean isDraw = (winner == null);

        for (Team team : Team.values()) {
            Team rival = team.opposite();

            int conceded = (int) match.getPlayersOf(team).stream()
                    .mapToLong(PlayerState::countDestroyedTowers).sum();
            int earned = (int) match.getPlayersOf(rival).stream()
                    .mapToLong(PlayerState::countDestroyedTowers).sum();

            boolean won = !isDraw && team == winner;
            boolean threeCrown = won && earned >= 3;

            for (PlayerState player : match.getPlayersOf(team)) {
                results.add(new MatchFinishedEvent.PlayerResult(
                        player.getUserId(),
                        team.name(),
                        won,
                        isDraw,
                        earned,
                        conceded,
                        threeCrown,
                        rewardCalculator.trophyChange(won, isDraw),
                        rewardCalculator.experienceGained(won, isDraw)
                ));
            }
        }

        double played = matchDurationSeconds - Math.max(0, match.getRemainingSeconds());

        return new MatchFinishedEvent(
                match.getMatchId(),
                winner != null ? winner.name() : null,
                played,
                System.currentTimeMillis(),
                results);
    }

    // ==================== Snapshots y emisión ====================

    private void broadcastState(GameMatch match) {
        MatchSnapshotDTO snapshot = buildSnapshot(match.getMatchId());
        messagingTemplate.convertAndSend("/topic/match/" + match.getMatchId(), snapshot);
    }

    /**
     * Foto del estado actual. Llamable desde cualquier thread: lee volatile y
     * colecciones concurrentes, nunca muta nada.
     */
    public MatchSnapshotDTO buildSnapshot(String matchId) {
        GameMatch match = getMatchOrThrow(matchId);

        List<PlayerSnapshotDTO> players = new ArrayList<>();
        List<TowerSnapshotDTO> towers = new ArrayList<>();

        match.getPlayersByTeam().forEach((team, playerList) -> {
            for (PlayerState p : playerList) {
                players.add(new PlayerSnapshotDTO(
                        p.getUserId(), team.name(), p.getElixir(),
                        p.getHand().stream().map(CardSnapshot::getCardId).toList(),
                        p.getCycleQueue().peekFirst() != null
                                ? p.getCycleQueue().peekFirst().getCardId() : null));
                for (TowerState t : p.getTowers()) {
                    towers.add(new TowerSnapshotDTO(
                            t.getType().name(), team.name(),
                            t.getPosition().x(), t.getPosition().y(),
                            t.getCurrentHealth(), t.getMaxHealth(), t.isDestroyed()));
                }
            }
        });

        List<UnitSnapshotDTO> units = match.getUnits().values().stream()
                .map(u -> new UnitSnapshotDTO(
                        u.getInstanceId(), u.getCard().getCardId(), u.getCard().getName(),
                        u.getTeam().name(), u.getPosition().x(), u.getPosition().y(),
                        u.getCurrentHealth(),
                        u.getCard().getHealth() != null ? u.getCard().getHealth() : 1,
                        u.getState().name()))
                .toList();

        return new MatchSnapshotDTO(
                match.getMatchId(), match.getStatus().name(),
                Math.max(0, match.getRemainingSeconds()),
                match.getWinner() != null ? match.getWinner().name() : null,
                players, towers, units);
    }

    // ==================== Helpers ====================

    private int damageOf(DeployedUnit unit) {
        return unit.getCard().getDamage() != null ? unit.getCard().getDamage() : 0;
    }

    /** ¿La unidad vuela? Los hechizos y edificios nunca. */
    private boolean isAerialUnit(DeployedUnit unit) {
        return Boolean.TRUE.equals(unit.getCard().getIsAerial());
    }

    /**
     * ¿El atacante puede atacar a ese objetivo?
     *
     *  - BUILDINGS_ONLY: solo edificios (un Giant ignora tropas, pero SÍ pega
     *    a un Cannon enemigo).
     *  - GROUND: solo objetivos terrestres. Los edificios cuentan como
     *    terrestres.
     *  - AIR_AND_GROUND (o null): todo.
     */
    private boolean canTarget(DeployedUnit attacker, DeployedUnit target) {
        String targetType = attacker.getCard().getTarget();
        boolean targetIsBuilding = "BUILDING".equals(target.getCard().getType());
        boolean targetIsAerial = isAerialUnit(target);

        if ("BUILDINGS_ONLY".equals(targetType)) {
            return targetIsBuilding;
        }
        if ("GROUND".equals(targetType)) {
            return !targetIsAerial;
        }
        return true;
    }

    /** Identidad estable de una torre: id de obstáculo y de objetivo. */
    private String towerId(TowerState tower) {
        return "TOWER:" + tower.getTeam() + ":" + tower.getType();
    }

    private double towerRadius(TowerState tower) {
        return tower.getType() == TowerType.KING
                ? GameConstants.KING_TOWER_RADIUS
                : GameConstants.PRINCESS_TOWER_RADIUS;
    }

    /** Identidad de una unidad como obstáculo (solo aplica a edificios). */
    private String unitObstacleId(DeployedUnit unit) {
        return "UNIT:" + unit.getInstanceId();
    }

    /** Posición continua → celda del grid que la contiene. */
    private Cell positionToCell(Position pos) {
        int col = clamp((int) Math.floor(pos.x()), 0, GameConstants.BOARD_WIDTH - 1);
        int row = clamp((int) Math.floor(pos.y()), 0, GameConstants.BOARD_HEIGHT - 1);
        return new Cell(col, row);
    }

    /** Centro de una celda como posición continua. */
    private Position cellCenter(Cell cell) {
        return new Position(cell.col() + 0.5, cell.row() + 0.5);
    }

    /** Evita que el empuje de colisión saque una unidad del tablero. */
    private Position clampToBoard(Position p) {
        double x = Math.max(0, Math.min(GameConstants.BOARD_WIDTH, p.x()));
        double y = Math.max(0, Math.min(GameConstants.BOARD_HEIGHT, p.y()));
        return new Position(x, y);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}