package edu.eci.arsw.RoyalArena.pathfinding;

import org.springframework.stereotype.Component;

import edu.eci.arsw.RoyalArena.model.GameConstants;


/**
 * Representa el terreno ESTÁTICO del tablero como un grid de celdas
 * transitables / bloqueadas. El único obstáculo por ahora es el río:
 * sus celdas están bloqueadas excepto las de los dos puentes.
 */
@Component
public class GameGrid {

    private static final int RIVER_ROW_START = (int) GameConstants.RIVER_Y_MIN;  // 15
    private static final int RIVER_ROW_END = (int) GameConstants.RIVER_Y_MAX;    // 17 (exclusivo)
    private static final int LEFT_BRIDGE_COL = (int) GameConstants.LEFT_BRIDGE_X;  // 3
    private static final int RIGHT_BRIDGE_COL = (int) GameConstants.RIGHT_BRIDGE_X; // 14

    private final int width = GameConstants.BOARD_WIDTH;   // 18
    private final int height = GameConstants.BOARD_HEIGHT; // 32
    private final boolean[][] walkable;

    public GameGrid() {
        this.walkable = new boolean[width][height];
        for (int col = 0; col < width; col++) {
            for (int row = 0; row < height; row++) {
                walkable[col][row] = computeWalkable(col, row);
            }
        }
    }

    /**
     * Una celda es transitable salvo que sea agua. Las filas del río
     * (15 y 16) solo son transitables en las columnas de los puentes.
     */
    private boolean computeWalkable(int col, int row) {
        boolean isRiver = row >= RIVER_ROW_START && row < RIVER_ROW_END;
        if (!isRiver) {
            return true;
        }
        return col == LEFT_BRIDGE_COL || col == RIGHT_BRIDGE_COL;
    }

    public boolean isWalkable(int col, int row) {
        return inBounds(col, row) && walkable[col][row];
    }

    public boolean inBounds(int col, int row) {
        return col >= 0 && col < width && row >= 0 && row < height;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
}