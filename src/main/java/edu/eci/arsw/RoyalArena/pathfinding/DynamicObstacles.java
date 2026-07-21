package edu.eci.arsw.RoyalArena.pathfinding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import edu.eci.arsw.RoyalArena.model.Cell;
import edu.eci.arsw.RoyalArena.model.records.Position;

/**
 * Obstáculos DINÁMICOS de una partida: torres vivas y edificios desplegados.
 * A diferencia del GameGrid (terreno fijo, compartido), esto es propio de
 * cada GameMatch y cambia durante el juego.
 *
 * Cada obstáculo se registra con un id ("TOWER:TEAM_A:KING", "UNIT:<uuid>")
 * y ocupa las celdas dentro de su radio. Al removerlo, sus celdas se liberan.
 *
 * Versionado: cada cambio incrementa 'version'. Las unidades guardan la
 * versión con la que calcularon su ruta; si difiere, recalculan. Así evitamos
 * recalcular en cada tick pero respondemos cuando el terreno cambia de verdad.
 *
 * Thread-safety: solo el thread del tick de la partida escribe (patrón
 * single-writer). Las lecturas del pathfinder ocurren en ese mismo thread.
 */
public class DynamicObstacles {

    /** Celdas ocupadas → id del obstáculo que las ocupa. */
    private final Map<Cell, String> occupiedBy = new HashMap<>();

    /** Id del obstáculo → celdas que ocupa (para poder liberarlas). */
    private final Map<String, Set<Cell>> cellsOf = new HashMap<>();

    /** Id del obstáculo → su radio en tiles (para el cálculo de rango efectivo). */
    private final Map<String, Double> radiusOf = new HashMap<>();

    private volatile int version = 0;

    /**
     * Registra un obstáculo circular: marca como ocupadas todas las celdas
     * cuyo centro cae dentro del radio.
     */
    public void addObstacle(String id, Position center, double radius) {
        Set<Cell> cells = new HashSet<>();
        int minCol = (int) Math.floor(center.x() - radius);
        int maxCol = (int) Math.ceil(center.x() + radius);
        int minRow = (int) Math.floor(center.y() - radius);
        int maxRow = (int) Math.ceil(center.y() + radius);

        for (int col = minCol; col <= maxCol; col++) {
            for (int row = minRow; row <= maxRow; row++) {
                Position cellCenter = new Position(col + 0.5, row + 0.5);
                if (cellCenter.distanceTo(center) <= radius) {
                    Cell cell = new Cell(col, row);
                    cells.add(cell);
                    occupiedBy.put(cell, id);
                }
            }
        }
        cellsOf.put(id, cells);
        radiusOf.put(id, radius);
        version++;
    }

    /**
     * Libera las celdas de un obstáculo (torre destruida, edificio muerto).
     */
    public void removeObstacle(String id) {
        Set<Cell> cells = cellsOf.remove(id);
        if (cells == null) {
            return;
        }
        for (Cell cell : cells) {
            // Solo liberar si sigue siendo de este obstáculo
            if (id.equals(occupiedBy.get(cell))) {
                occupiedBy.remove(cell);
            }
        }
        radiusOf.remove(id);
        version++;
    }

    /**
     * ¿La celda está bloqueada? Si se pasa exemptId, ese obstáculo se ignora
     * (se usa cuando el objetivo de la unidad ES ese obstáculo: no queremos
     * que su propio blanco le bloquee la ruta).
     */
    public boolean isBlocked(Cell cell, String exemptId) {
        String owner = occupiedBy.get(cell);
        if (owner == null) {
            return false;
        }
        return !owner.equals(exemptId);
    }

    public boolean isBlocked(Cell cell) {
        return occupiedBy.containsKey(cell);
    }

    /** Radio del obstáculo, o 0 si no existe. Para el rango de ataque efectivo. */
    public double radiusOf(String id) {
        return radiusOf.getOrDefault(id, 0.0);
    }

    public int getVersion() {
        return version;
    }
}