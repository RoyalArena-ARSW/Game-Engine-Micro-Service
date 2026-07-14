package edu.eci.arsw.RoyalArena.model;

/**
 * Constantes del juego, centralizadas para ajustar el balance en un solo lugar.
 * Valores basados en Clash Royale a nivel torneo (aproximados).
 */
public final class GameConstants {

    private GameConstants() {} // No instanciable

    // ============ Tablero ============
    public static final int BOARD_WIDTH = 18;
    public static final int BOARD_HEIGHT = 32;
    /** El río ocupa las filas 15 y 16. Solo se cruza por los puentes. */
    public static final double RIVER_Y_MIN = 15.0;
    public static final double RIVER_Y_MAX = 17.0;
    /** Columnas (centro) de los dos puentes. */
    public static final double LEFT_BRIDGE_X = 3.5;
    public static final double RIGHT_BRIDGE_X = 14.5;

    // ============ Elixir ============
    public static final double MAX_ELIXIR = 10.0;
    public static final double INITIAL_ELIXIR = 5.0;
    /** Un elixir cada 2.8 segundos (estándar de CR en tiempo normal). */
    public static final double ELIXIR_PER_SECOND = 1.0 / 2.8;

    // ============ Torres (HP aprox. nivel torneo) ============
    public static final int KING_TOWER_HP = 4824;
    public static final int PRINCESS_TOWER_HP = 3052;
    public static final int TOWER_DAMAGE = 109;
    public static final double TOWER_ATTACK_RANGE = 7.5;
    public static final double TOWER_ATTACK_SPEED_SECONDS = 0.8;

    // ============ Posiciones de torres (TEAM_A abajo, TEAM_B arriba) ============
    public static final double KING_A_X = 9.0,  KING_A_Y = 2.5;
    public static final double PRINCESS_A_LEFT_X = 3.5,  PRINCESS_A_Y = 6.5;
    public static final double PRINCESS_A_RIGHT_X = 14.5;
    public static final double KING_B_X = 9.0,  KING_B_Y = 29.5;
    public static final double PRINCESS_B_LEFT_X = 3.5,  PRINCESS_B_Y = 25.5;
    public static final double PRINCESS_B_RIGHT_X = 14.5;

    // ============ Mano de cartas ============
    public static final int HAND_SIZE = 4;
    public static final int DECK_SIZE = 8;
}