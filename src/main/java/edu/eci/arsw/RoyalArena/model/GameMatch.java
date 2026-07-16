package edu.eci.arsw.RoyalArena.model;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import edu.eci.arsw.RoyalArena.model.enums.MatchStatus;
import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.records.PlayerAction;
import edu.eci.arsw.RoyalArena.pathfinding.DynamicObstacles;

import lombok.Getter;
import lombok.Setter;

/**
 * Una partida en curso. Vive EN MEMORIA (dentro del ConcurrentHashMap del
 * GameEngineService) durante los ~3 minutos que dura, y luego se limpia.
 * Nunca se persiste: los eventos relevantes se publican a RabbitMQ y el
 * Replay Service los persiste (Fase 4).
 *
 * Diseño 1v1 extensible a 2v2: los jugadores se agrupan por Team en un Map.
 * En 1v1 cada equipo tiene un jugador; en 2v2 tendría dos, y la lógica de
 * "unidades del equipo contrario" no cambia.
 *
 * Concurrencia:
 * - pendingActions: cola concurrente donde los threads de WebSocket ENCOLAN
 *   acciones. El game loop las drena al inicio de cada tick.
 * - units: ConcurrentHashMap para lecturas seguras desde los snapshots.
 * - obstacles: obstáculos dinámicos de ESTA partida (torres y edificios).
 * - El resto del estado lo escribe SOLO el thread del tick (single-writer).
 */
@Getter
public class GameMatch {

    private final String matchId;
    private final Map<Team, List<PlayerState>> playersByTeam;

    /** Todas las unidades vivas del tablero, indexadas por instanceId. */
    private final Map<String, DeployedUnit> units = new ConcurrentHashMap<>();

    /** Acciones de jugadores pendientes de procesar en el próximo tick. */
    private final Queue<PlayerAction> pendingActions = new ConcurrentLinkedQueue<>();

    /** Obstáculos dinámicos de esta partida: torres vivas y edificios. */
    private final DynamicObstacles obstacles = new DynamicObstacles();

    @Setter
    private volatile MatchStatus status;

    @Setter
    private volatile double remainingSeconds;

    /** Team ganador; null mientras la partida siga o si terminó en empate. */
    @Setter
    private volatile Team winner;

    private final long createdAtMs;

    public GameMatch(PlayerState playerA, PlayerState playerB, double durationSeconds) {
        this.matchId = UUID.randomUUID().toString();
        this.playersByTeam = Map.of(
                Team.TEAM_A, List.of(playerA),
                Team.TEAM_B, List.of(playerB)
        );
        this.status = MatchStatus.WAITING;
        this.remainingSeconds = durationSeconds;
        this.createdAtMs = System.currentTimeMillis();
    }

    /**
     * Encola una acción de jugador. Llamado desde los threads de WebSocket.
     * El game loop la procesará en su próximo tick.
     */
    public void enqueueAction(PlayerAction action) {
        pendingActions.offer(action);
    }

    /** Busca el PlayerState de un userId, en cualquier equipo. */
    public PlayerState findPlayer(Long userId) {
        return playersByTeam.values().stream()
                .flatMap(List::stream)
                .filter(p -> p.getUserId().equals(userId))
                .findFirst()
                .orElse(null);
    }

    /** Todos los jugadores de un equipo (1 en 1v1, 2 en el futuro 2v2). */
    public List<PlayerState> getPlayersOf(Team team) {
        return playersByTeam.get(team);
    }

    public boolean isInProgress() {
        return status == MatchStatus.IN_PROGRESS;
    }
}