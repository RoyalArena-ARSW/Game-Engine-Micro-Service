package edu.eci.arsw.RoyalArena.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.eci.arsw.RoyalArena.model.GameConstants;

/**
 * Verifica el terreno estático: el río bloquea, los puentes no.
 */
class GameGridTest {

    private GameGrid grid;

    @BeforeEach
    void setUp() {
        grid = new GameGrid();
    }

    @Test
    @DisplayName("Las filas del rio estan bloqueadas fuera de los puentes")
    void riverRowsAreBlocked() {
        // Filas 15 y 16 son agua
        for (int col = 0; col < GameConstants.BOARD_WIDTH; col++) {
            boolean isBridge = (col == 3 || col == 14);
            assertThat(grid.isWalkable(col, 15))
                    .as("celda (%d, 15)", col)
                    .isEqualTo(isBridge);
            assertThat(grid.isWalkable(col, 16))
                    .as("celda (%d, 16)", col)
                    .isEqualTo(isBridge);
        }
    }

    @Test
    @DisplayName("Los puentes cruzan ambas filas del rio")
    void bridgesSpanBothRiverRows() {
        assertThat(grid.isWalkable(3, 15)).isTrue();
        assertThat(grid.isWalkable(3, 16)).isTrue();
        assertThat(grid.isWalkable(14, 15)).isTrue();
        assertThat(grid.isWalkable(14, 16)).isTrue();
    }

    @Test
    @DisplayName("Todo lo que no es rio es transitable")
    void nonRiverCellsAreWalkable() {
        for (int col = 0; col < GameConstants.BOARD_WIDTH; col++) {
            for (int row = 0; row < GameConstants.BOARD_HEIGHT; row++) {
                if (row == 15 || row == 16) continue;
                assertThat(grid.isWalkable(col, row))
                        .as("celda (%d, %d)", col, row)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Fuera de los limites nunca es transitable")
    void outOfBoundsIsNeverWalkable() {
        assertThat(grid.isWalkable(-1, 5)).isFalse();
        assertThat(grid.isWalkable(5, -1)).isFalse();
        assertThat(grid.isWalkable(GameConstants.BOARD_WIDTH, 5)).isFalse();
        assertThat(grid.isWalkable(5, GameConstants.BOARD_HEIGHT)).isFalse();
    }
}