package edu.eci.arsw.RoyalArena.pathfinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import org.springframework.stereotype.Component;

import edu.eci.arsw.RoyalArena.model.Cell;

/**
 * Implementación del algoritmo A* para encontrar el camino más corto entre
 * dos celdas del tablero, rodeando los obstáculos (el río).
 */
@Component
public class AStarPathfinder {

    private static final double DIAGONAL_COST = Math.sqrt(2);

    private record OpenEntry(Cell cell, double fScore) { }

    /**
     * Calcula el camino más corto de start a goal, rodeando el terreno y los
     * obstáculos dinámicos.
     *
     * @param exemptObstacleId obstáculo que NO cuenta como bloqueo (el objetivo
     *                         de la unidad: si va a atacar una torre, esa torre
     *                         no debe bloquearle la ruta hacia ella).
     */
    public List<Cell> findPath(GameGrid grid, DynamicObstacles obstacles,
                                Cell start, Cell goal, String exemptObstacleId) {
        Cell target = isFree(grid, obstacles, goal, exemptObstacleId)
                ? goal
                : nearestFree(grid, obstacles, goal, exemptObstacleId);
        if (target == null) {
            return List.of();
        }

        Map<Cell, Double> gScore = new HashMap<>();
        Map<Cell, Cell> cameFrom = new HashMap<>();
        Set<Cell> closed = new HashSet<>();

        gScore.put(start, 0.0);
        PriorityQueue<OpenEntry> open =
                new PriorityQueue<>(Comparator.comparingDouble(OpenEntry::fScore));
        open.add(new OpenEntry(start, heuristic(start, target)));

        while (!open.isEmpty()) {
            Cell current = open.poll().cell();

            if (current.equals(target)) {
                return reconstructPath(cameFrom, current);
            }
            if (!closed.add(current)) {
                continue;
            }

            for (Cell neighbor : freeNeighbors(grid, obstacles, current, exemptObstacleId)) {
                if (closed.contains(neighbor)) {
                    continue;
                }
                double tentativeG = gScore.get(current) + moveCost(current, neighbor);
                if (tentativeG < gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    cameFrom.put(neighbor, current);
                    gScore.put(neighbor, tentativeG);
                    open.add(new OpenEntry(neighbor, tentativeG + heuristic(neighbor, target)));
                }
            }
        }
        return List.of();
    }

    /**
     * Una celda está libre si el terreno la permite y ningún obstáculo
     * dinámico (salvo el exento) la ocupa.
     */
    private boolean isFree(GameGrid grid, DynamicObstacles obstacles,
                            Cell cell, String exemptId) {
        return grid.isWalkable(cell.col(), cell.row())
                && !obstacles.isBlocked(cell, exemptId);
    }

    private List<Cell> freeNeighbors(GameGrid grid, DynamicObstacles obstacles,
                                      Cell c, String exemptId) {
        List<Cell> result = new ArrayList<>();
        for (int dc = -1; dc <= 1; dc++) {
            for (int dr = -1; dr <= 1; dr++) {
                if (dc == 0 && dr == 0) {
                    continue;
                }
                Cell n = new Cell(c.col() + dc, c.row() + dr);
                if (!isFree(grid, obstacles, n, exemptId)) {
                    continue;
                }
                // Sin corner cutting: en diagonal, ambas ortogonales deben estar libres
                if (dc != 0 && dr != 0) {
                    if (!isFree(grid, obstacles, new Cell(c.col() + dc, c.row()), exemptId)
                            || !isFree(grid, obstacles, new Cell(c.col(), c.row() + dr), exemptId)) {
                        continue;
                    }
                }
                result.add(n);
            }
        }
        return result;
    }

    /**
     * Celda libre más cercana a una dada (BFS en anillo). Se usa cuando el
     * destino está sobre un obstáculo: la unidad va al borde.
     */
    private Cell nearestFree(GameGrid grid, DynamicObstacles obstacles,
                              Cell from, String exemptId) {
        Queue<Cell> queue = new LinkedList<>();
        Set<Cell> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            Cell c = queue.poll();
            if (isFree(grid, obstacles, c, exemptId)) {
                return c;
            }
            for (int dc = -1; dc <= 1; dc++) {
                for (int dr = -1; dr <= 1; dr++) {
                    if (dc == 0 && dr == 0) {
                        continue;
                    }
                    Cell n = new Cell(c.col() + dc, c.row() + dr);
                    if (grid.inBounds(n.col(), n.row()) && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Heurística: distancia euclidiana (línea recta) entre dos celdas.
     * Nunca sobreestima el costo real → admisible → A* es óptimo.
     */
    private double heuristic(Cell a, Cell b) {
        double dc = a.col() - b.col();
        double dr = a.row() - b.row();
        return Math.sqrt(dc * dc + dr * dr);
    }

    /**
     * Costo de moverse a una celda adyacente: 1 si es ortogonal, √2 si diagonal.
     */
    private double moveCost(Cell a, Cell b) {
        int dc = Math.abs(a.col() - b.col());
        int dr = Math.abs(a.row() - b.row());
        return (dc + dr == 2) ? DIAGONAL_COST : 1.0;
    }

    /**
     * Vecinos transitables en 8 direcciones, evitando el corner cutting.
     */
    private List<Cell> walkableNeighbors(GameGrid grid, Cell c) {
        List<Cell> result = new ArrayList<>();
        for (int dc = -1; dc <= 1; dc++) {
            for (int dr = -1; dr <= 1; dr++) {
                if (dc == 0 && dr == 0) {
                    continue;
                }
                int nc = c.col() + dc;
                int nr = c.row() + dr;
                if (!grid.isWalkable(nc, nr)) {
                    continue;
                }
                // Evitar cruzar en diagonal por la esquina de un obstáculo:
                // ambas celdas ortogonales adyacentes deben ser transitables.
                if (dc != 0 && dr != 0) {
                    if (!grid.isWalkable(c.col() + dc, c.row())
                            || !grid.isWalkable(c.col(), c.row() + dr)) {
                        continue;
                    }
                }
                result.add(new Cell(nc, nr));
            }
        }
        return result;
    }

    /**
     * Reconstruye el camino siguiendo cameFrom hacia atrás desde el destino
     * y luego lo invierte (para que quede de origen a destino).
     */
    private List<Cell> reconstructPath(Map<Cell, Cell> cameFrom, Cell current) {
        LinkedList<Cell> path = new LinkedList<>();
        path.addFirst(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.addFirst(current);
        }
        return path;
    }

    /**
     * BFS en anillo hacia afuera para hallar la celda transitable más cercana
     * a una celda dada. Red de seguridad por si el objetivo cae sobre agua.
     */
    private Cell nearestWalkable(GameGrid grid, Cell from) {
        Queue<Cell> queue = new LinkedList<>();
        Set<Cell> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            Cell c = queue.poll();
            if (grid.isWalkable(c.col(), c.row())) {
                return c;
            }
            for (int dc = -1; dc <= 1; dc++) {
                for (int dr = -1; dr <= 1; dr++) {
                    if (dc == 0 && dr == 0) {
                        continue;
                    }
                    Cell n = new Cell(c.col() + dc, c.row() + dr);
                    if (grid.inBounds(n.col(), n.row()) && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        return null;
    }
}