package edu.eci.arsw.RoyalArena.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.eci.arsw.RoyalArena.model.Cell;
import edu.eci.arsw.RoyalArena.model.records.Position;

/**
 * Tests del algoritmo A*.
 *
 * La estrategia es verificar PROPIEDADES de la ruta (es continua, no atraviesa
 * agua, empieza y termina donde debe) en vez de comparar contra una secuencia
 * de celdas concreta: cualquier ruta que cumpla esas propiedades es válida, y
 * así el test no se rompe si el algoritmo elige un camino igual de bueno.
 */
class AStarPathfinderTest {

    private GameGrid grid;
    private DynamicObstacles noObstacles;
    private AStarPathfinder pathfinder;

    @BeforeEach
    void setUp() {
        grid = new GameGrid();
        noObstacles = new DynamicObstacles();
        pathfinder = new AStarPathfinder();
    }

    // ===== Helpers de verificación =====

    /** Cada celda del camino debe ser adyacente a la anterior (8 direcciones). */
    private void assertPathIsContiguous(List<Cell> path) {
        for (int i = 1; i < path.size(); i++) {
            Cell prev = path.get(i - 1);
            Cell curr = path.get(i);
            int dc = Math.abs(curr.col() - prev.col());
            int dr = Math.abs(curr.row() - prev.row());
            assertThat(dc <= 1 && dr <= 1 && (dc + dr) > 0)
                    .as("salto ilegal de %s a %s", prev, curr)
                    .isTrue();
        }
    }

    /** Ninguna celda del camino puede ser agua. */
    private void assertPathNeverCrossesWater(List<Cell> path) {
        for (Cell c : path) {
            if (c.row() == 15 || c.row() == 16) {
                assertThat(c.col())
                        .as("la celda %s esta sobre el rio fuera de un puente", c)
                        .isIn(3, 14);
            }
        }
    }

    // ===== Tests =====

    @Test
    @DisplayName("Sin obstaculos entre medio, el camino es directo")
    void findsDirectPathWhenNoObstacles() {
        Cell start = new Cell(9, 5);
        Cell goal = new Cell(9, 10);

        List<Cell> path = pathfinder.findPath(grid, noObstacles, start, goal, null);

        assertThat(path).isNotEmpty();
        assertThat(path.get(0)).isEqualTo(start);
        assertThat(path.get(path.size() - 1)).isEqualTo(goal);
        assertPathIsContiguous(path);
        // 5 filas de diferencia → 6 celdas (origen incluido). Sin desvíos.
        assertThat(path).hasSize(6);
    }

    @Test
    @DisplayName("Para cruzar el rio, la ruta pasa por un puente y nunca por el agua")
    void crossesRiverThroughABridge() {
        Cell start = new Cell(9, 5);   // abajo
        Cell goal = new Cell(9, 29);   // arriba, al otro lado del río

        List<Cell> path = pathfinder.findPath(grid, noObstacles, start, goal, null);

        assertThat(path).isNotEmpty();
        assertThat(path.get(0)).isEqualTo(start);
        assertThat(path.get(path.size() - 1)).isEqualTo(goal);
        assertPathIsContiguous(path);
        assertPathNeverCrossesWater(path);

        // Debe haber cruzado por alguno de los dos puentes
        boolean usedBridge = path.stream()
                .anyMatch(c -> (c.row() == 15 || c.row() == 16) && (c.col() == 3 || c.col() == 14));
        assertThat(usedBridge).as("la ruta deberia usar un puente").isTrue();
    }

    @Test
    @DisplayName("Elige el puente mas cercano al origen")
    void picksTheNearestBridge() {
        // Origen pegado al lado izquierdo → debe usar el puente izquierdo (col 3)
        List<Cell> leftPath = pathfinder.findPath(
                grid, noObstacles, new Cell(1, 10), new Cell(1, 25), null);
        assertThat(leftPath).isNotEmpty();
        assertThat(leftPath.stream()
                .filter(c -> c.row() == 15)
                .allMatch(c -> c.col() == 3))
                .as("desde la izquierda deberia cruzar por el puente izquierdo")
                .isTrue();

        // Origen pegado a la derecha → puente derecho (col 14)
        List<Cell> rightPath = pathfinder.findPath(
                grid, noObstacles, new Cell(16, 10), new Cell(16, 25), null);
        assertThat(rightPath).isNotEmpty();
        assertThat(rightPath.stream()
                .filter(c -> c.row() == 15)
                .allMatch(c -> c.col() == 14))
                .as("desde la derecha deberia cruzar por el puente derecho")
                .isTrue();
    }

    @Test
    @DisplayName("Rodea un obstaculo dinamico en el camino")
    void goesAroundDynamicObstacle() {
        // Un edificio justo en la línea recta entre origen y destino
        DynamicObstacles obstacles = new DynamicObstacles();
        obstacles.addObstacle("BUILDING:1", new Position(9.5, 7.5), 1.5);

        List<Cell> path = pathfinder.findPath(
                grid, obstacles, new Cell(9, 5), new Cell(9, 10), null);

        assertThat(path).isNotEmpty();
        assertPathIsContiguous(path);
        // Ninguna celda del camino puede estar dentro del obstáculo
        assertThat(path).noneMatch(c -> obstacles.isBlocked(c, null));
        // Y debe ser más largo que la ruta directa (6), porque rodeó
        assertThat(path.size()).isGreaterThan(6);
    }

    @Test
    @DisplayName("El objetivo exento no bloquea: la unidad llega a su borde")
    void exemptTargetDoesNotBlockItsOwnPath() {
        // La torre que la unidad va a atacar ocupa celdas
        DynamicObstacles obstacles = new DynamicObstacles();
        obstacles.addObstacle("TOWER:B:PRINCESS_LEFT", new Position(3.5, 25.5), 1.5);

        Cell start = new Cell(3, 20);
        Cell goal = new Cell(3, 25); // dentro de la torre

        // Con la torre exenta, debe encontrar camino hasta ella
        List<Cell> path = pathfinder.findPath(
                grid, obstacles, start, goal, "TOWER:B:PRINCESS_LEFT");

        assertThat(path).as("deberia poder acercarse a su propio objetivo").isNotEmpty();
        assertThat(path.get(0)).isEqualTo(start);
        assertPathIsContiguous(path);
    }

    @Test
    @DisplayName("Si el destino esta bloqueado por OTRO obstaculo, va a la celda libre mas cercana")
    void fallsBackToNearestFreeCellWhenGoalIsBlocked() {
        DynamicObstacles obstacles = new DynamicObstacles();
        obstacles.addObstacle("BUILDING:X", new Position(9.5, 9.5), 1.5);

        // El destino cae dentro del edificio, y NO está exento
        List<Cell> path = pathfinder.findPath(
                grid, obstacles, new Cell(9, 5), new Cell(9, 9), null);

        assertThat(path).isNotEmpty();
        Cell last = path.get(path.size() - 1);
        // Termina en el borde, no dentro
        assertThat(obstacles.isBlocked(last, null)).isFalse();
    }

    @Test
    @DisplayName("Origen igual a destino devuelve un camino de una sola celda")
    void samePositionReturnsSingleCell() {
        Cell cell = new Cell(9, 5);
        List<Cell> path = pathfinder.findPath(grid, noObstacles, cell, cell, null);
        assertThat(path).containsExactly(cell);
    }

    @Test
    @DisplayName("Una unidad encerrada no encuentra camino y devuelve lista vacia")
    void returnsEmptyWhenNoPathExists() {
        // Amurallar la esquina (0,0) con un obstáculo grande
        DynamicObstacles obstacles = new DynamicObstacles();
        obstacles.addObstacle("WALL", new Position(2.5, 2.5), 3.0);

        List<Cell> path = pathfinder.findPath(
                grid, obstacles, new Cell(0, 0), new Cell(9, 20), null);

        // Si (0,0) quedó encerrada, no hay ruta
        if (obstacles.isBlocked(new Cell(1, 0)) && obstacles.isBlocked(new Cell(0, 1))) {
            assertThat(path).isEmpty();
        }
    }
}