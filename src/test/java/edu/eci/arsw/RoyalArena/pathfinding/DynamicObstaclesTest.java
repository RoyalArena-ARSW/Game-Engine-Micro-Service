package edu.eci.arsw.RoyalArena.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.eci.arsw.RoyalArena.model.Cell;
import edu.eci.arsw.RoyalArena.model.records.Position;

class DynamicObstaclesTest {

    private DynamicObstacles obstacles;

    @BeforeEach
    void setUp() {
        obstacles = new DynamicObstacles();
    }

    @Test
    @DisplayName("Un obstaculo bloquea las celdas dentro de su radio")
    void obstacleBlocksCellsWithinRadius() {
        obstacles.addObstacle("TOWER:X", new Position(9.5, 9.5), 1.5);

        // El centro cae dentro
        assertThat(obstacles.isBlocked(new Cell(9, 9))).isTrue();
        // Una celda lejana no
        assertThat(obstacles.isBlocked(new Cell(0, 0))).isFalse();
    }

    @Test
    @DisplayName("Remover un obstaculo libera sus celdas")
    void removingObstacleFreesCells() {
        obstacles.addObstacle("TOWER:X", new Position(9.5, 9.5), 1.5);
        assertThat(obstacles.isBlocked(new Cell(9, 9))).isTrue();

        obstacles.removeObstacle("TOWER:X");
        assertThat(obstacles.isBlocked(new Cell(9, 9))).isFalse();
    }

    @Test
    @DisplayName("El obstaculo exento no bloquea (es el objetivo de la unidad)")
    void exemptObstacleDoesNotBlock() {
        obstacles.addObstacle("TOWER:TARGET", new Position(9.5, 9.5), 1.5);

        assertThat(obstacles.isBlocked(new Cell(9, 9), "TOWER:TARGET")).isFalse();
        assertThat(obstacles.isBlocked(new Cell(9, 9), "OTRO")).isTrue();
    }

    @Test
    @DisplayName("La version incrementa al agregar y remover")
    void versionChangesOnMutation() {
        int v0 = obstacles.getVersion();

        obstacles.addObstacle("A", new Position(5.5, 5.5), 1.0);
        int v1 = obstacles.getVersion();
        assertThat(v1).isGreaterThan(v0);

        obstacles.removeObstacle("A");
        assertThat(obstacles.getVersion()).isGreaterThan(v1);
    }

    @Test
    @DisplayName("Remover un obstaculo inexistente es inocuo (idempotencia)")
    void removingUnknownObstacleIsNoOp() {
        int before = obstacles.getVersion();
        obstacles.removeObstacle("NO_EXISTE");
        assertThat(obstacles.getVersion()).isEqualTo(before);
    }

    @Test
    @DisplayName("radiusOf devuelve el radio registrado")
    void radiusIsTracked() {
        obstacles.addObstacle("TOWER:K", new Position(9.0, 2.5), 2.0);
        assertThat(obstacles.radiusOf("TOWER:K")).isEqualTo(2.0);
        assertThat(obstacles.radiusOf("NO_EXISTE")).isEqualTo(0.0);
    }
}