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
import org.springframework.stereotype.Service;

import edu.eci.arsw.RoyalArena.dto.MatchSnapshotDTO;
import edu.eci.arsw.RoyalArena.dto.PlayerSnapshotDTO;
import edu.eci.arsw.RoyalArena.dto.TowerSnapshotDTO;
import edu.eci.arsw.RoyalArena.dto.UnitSnapshotDTO;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;
import edu.eci.arsw.RoyalArena.model.DeployedUnit;
import edu.eci.arsw.RoyalArena.model.GameConstants;
import edu.eci.arsw.RoyalArena.model.GameMatch;

import edu.eci.arsw.RoyalArena.model.PlayerState;

import edu.eci.arsw.RoyalArena.model.TowerState;
import edu.eci.arsw.RoyalArena.model.enums.MatchStatus;
import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.UnitState;
import edu.eci.arsw.RoyalArena.model.records.PlayerAction;
import edu.eci.arsw.RoyalArena.model.records.Position;
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
 *    Resultado: cero race conditions sobre la lógica del juego, sin
 *    synchronized en el combate.
 *
 * 3. ScheduledExecutorService COMPARTIDO: N threads atienden M partidas
 *    (N << M). Cada partida agenda su tick con scheduleAtFixedRate.
 *    Importante: los ticks de UNA partida nunca se solapan entre sí
 *    (scheduleAtFixedRate garantiza no-concurrencia de la misma tarea),
 *    pero ticks de partidas DISTINTAS sí corren en paralelo en el pool.
 *
 * 4. Los lectores (snapshots para clientes) leen campos volatile y
 *    colecciones concurrentes: pueden ver un estado "a mitad de tick"
 *    pero nunca corrupto.
 */
@Slf4j
@Service
public class GameEngineService {

    private final Map<String, GameMatch> activeMatches = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> matchTasks = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    @Value("${game.engine.thread-pool-size:4}")
    private int threadPoolSize;

    @Value("${game.tick.interval-ms:100}")
    private long tickIntervalMs;

    @Value("${game.match.duration-seconds:180}")
    private double matchDurationSeconds;

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
     * Crea una partida con los mazos ya resueltos (los CardSnapshots
     * vienen de Deck-and-Cards en Fase 4, o del TestDataFactory ahora).
     */
    public GameMatch createMatch(Long userA, List<CardSnapshot> deckA,
                                  Long userB, List<CardSnapshot> deckB) {
        PlayerState playerA = new PlayerState(userA, Team.TEAM_A, deckA);
        PlayerState playerB = new PlayerState(userB, Team.TEAM_B, deckB);
        GameMatch match = new GameMatch(playerA, playerB, matchDurationSeconds);
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
     * Encola la acción de un jugador. Llamable desde cualquier thread
     * (REST ahora, WebSocket en Fase 2). NO muta el estado: el tick
     * la procesará.
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
     * Envoltorio del tick con manejo de errores: si un tick lanza una
     * excepción no capturada, scheduleAtFixedRate CANCELA silenciosamente
     * las siguientes ejecuciones — la partida se congelaría sin logs.
     * Por eso el try-catch aquí es crítico.
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

        // 1. Procesar acciones encoladas de los jugadores
        processPendingActions(match);

        // 2. Regenerar elixir de todos los jugadores
        match.getPlayersByTeam().values().stream()
                .flatMap(List::stream)
                .forEach(p -> p.regenerateElixir(deltaSeconds));

        // 3. Actualizar unidades: targeting, movimiento, combate
        updateUnits(match, deltaSeconds);

        // 4. Torres atacan unidades enemigas en rango
        updateTowers(match, deltaSeconds);

        // 5. Remover unidades muertas
        match.getUnits().values().removeIf(DeployedUnit::isDead);

        // 6. Descontar tiempo y chequear condiciones de fin
        match.setRemainingSeconds(match.getRemainingSeconds() - deltaSeconds);
        checkVictoryConditions(match);
    }

    // ==================== Fases del tick ====================

    private void processPendingActions(GameMatch match) {
        PlayerAction action;
        while ((action = match.getPendingActions().poll()) != null) {
            PlayerState player = match.findPlayer(action.playerId());
            if (player == null) continue;

            CardSnapshot card = player.findCardInDeck(action.cardId());
            if (card == null) continue;

            if (!isValidDeployment(player.getTeam(), action.position())) {
                log.debug("Invalid deployment position {} for player {}", action.position(), action.playerId());
                continue;
            }

            if (!player.tryPlayCard(action.cardId())) {
                log.debug("Player {} can't play card {} (elixir or not in hand)",
                        action.playerId(), action.cardId());
                continue;
            }

            deployCard(match, player, card, action.position());
        }
    }

    /**
     * Regla de despliegue: solo en tu propia mitad del tablero.
     * TEAM_A abajo (y < 15), TEAM_B arriba (y > 17). Sobre el río no.
     */
    private boolean isValidDeployment(Team team, Position pos) {
        if (pos.x() < 0 || pos.x() > GameConstants.BOARD_WIDTH
                || pos.y() < 0 || pos.y() > GameConstants.BOARD_HEIGHT) {
            return false;
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
        // Tropas con unitCount > 1 (Skeletons = 4) se despliegan en abanico
        int count = card.getUnitCount() != null ? card.getUnitCount() : 1;
        for (int i = 0; i < count; i++) {
            double offset = (i - (count - 1) / 2.0) * 0.7;
            Position spawn = new Position(pos.x() + offset, pos.y());
            DeployedUnit unit = new DeployedUnit(card, player.getTeam(), spawn);
            match.getUnits().put(unit.getInstanceId(), unit);
        }
        log.debug("Player {} deployed {} x{} at {}", player.getUserId(), card.getName(), count, pos);
    }

    /**
     * Hechizo simplificado: daño instantáneo a todas las unidades y torres
     * enemigas dentro del radio. (Duraciones/efectos continuos: mejora futura.)
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

    /**
     * Por cada unidad viva: elegir objetivo, moverse hacia él o atacarlo.
     */
    private void updateUnits(GameMatch match, double deltaSeconds) {
        for (DeployedUnit unit : match.getUnits().values()) {
            if (unit.isDead()) continue;

            unit.reduceCooldown(tickIntervalMs);
            Team enemyTeam = unit.getTeam().opposite();

            // ¿Objetivo unidad enemiga más cercana? (si la carta puede atacarlas)
            DeployedUnit nearestEnemyUnit = null;
            if (!"BUILDINGS_ONLY".equals(unit.getCard().getTarget())) {
                nearestEnemyUnit = match.getUnits().values().stream()
                        .filter(u -> u.getTeam() == enemyTeam && !u.isDead())
                        .min(Comparator.comparingDouble(u -> u.getPosition().distanceTo(unit.getPosition())))
                        .orElse(null);
            }

            // Torre enemiga más cercana (siempre es objetivo válido)
            TowerState nearestTower = match.getPlayersOf(enemyTeam).stream()
                    .flatMap(p -> p.getTowers().stream())
                    .filter(t -> !t.isDestroyed())
                    .min(Comparator.comparingDouble(t -> t.getPosition().distanceTo(unit.getPosition())))
                    .orElse(null);

            // Elegir el más cercano entre unidad y torre
            Position targetPos;
            Runnable attackAction;
            double distToUnit = nearestEnemyUnit != null
                    ? unit.getPosition().distanceTo(nearestEnemyUnit.getPosition()) : Double.MAX_VALUE;
            double distToTower = nearestTower != null
                    ? unit.getPosition().distanceTo(nearestTower.getPosition()) : Double.MAX_VALUE;

            if (distToUnit <= distToTower && nearestEnemyUnit != null) {
                targetPos = nearestEnemyUnit.getPosition();
                DeployedUnit target = nearestEnemyUnit;
                attackAction = () -> target.applyDamage(damageOf(unit));
            } else if (nearestTower != null) {
                targetPos = nearestTower.getPosition();
                TowerState target = nearestTower;
                attackAction = () -> target.applyDamage(damageOf(unit));
            } else {
                continue; // Sin objetivos: partida a punto de terminar
            }

            double range = unit.getCard().getAttackRange() != null
                    ? Math.max(unit.getCard().getAttackRange(), 0.8) : 0.8; // melee ~0.8 tiles
            double distance = unit.getPosition().distanceTo(targetPos);

            if (distance <= range) {
                unit.setState(UnitState.ATTACKING);
                if (unit.canAttack()) {
                    attackAction.run();
                    double cooldown = unit.getCard().getAttackSpeed() != null
                            ? unit.getCard().getAttackSpeed() * 1000 : 1000;
                    unit.setAttackCooldownMs(cooldown);
                }
            } else {
                unit.setState(UnitState.MOVING);
                unit.moveTowards(targetPos, deltaSeconds);
            }
        }
    }

    private int damageOf(DeployedUnit unit) {
        return unit.getCard().getDamage() != null ? unit.getCard().getDamage() : 0;
    }

    /**
     * Cada torre viva ataca a la unidad enemiga más cercana en su rango.
     */
    private void updateTowers(GameMatch match, double deltaSeconds) {
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

    /**
     * Fin de partida: torre del rey destruida (victoria instantánea) o
     * tiempo agotado (gana quien haya destruido más torres; empate posible).
     */
    private void checkVictoryConditions(GameMatch match) {
        boolean kingADown = match.getPlayersOf(Team.TEAM_A).stream().anyMatch(PlayerState::isKingTowerDestroyed);
        boolean kingBDown = match.getPlayersOf(Team.TEAM_B).stream().anyMatch(PlayerState::isKingTowerDestroyed);

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
        // Fase 4: aquí se publica MatchFinishedEvent a RabbitMQ.
        // La limpieza del map se hace diferida para que los clientes
        // puedan consultar el resultado final (mejora: scheduler de limpieza).
    }

    // ==================== Snapshots para clientes ====================

    /**
     * Construye la foto del estado actual. Llamable desde cualquier thread:
     * lee volatile y colecciones concurrentes, nunca muta nada.
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
}