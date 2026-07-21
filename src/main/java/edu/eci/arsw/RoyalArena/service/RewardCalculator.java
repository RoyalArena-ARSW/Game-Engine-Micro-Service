package edu.eci.arsw.RoyalArena.service;

import org.springframework.stereotype.Component;

/**
 * Reglas de recompensa. Vive en Game Engine porque es quien conoce el
 * resultado; los consumidores solo aplican el número que reciben.
 *
 * Centralizado aquí para poder balancear el juego en un solo lugar.
 */
@Component
public class RewardCalculator {

    private static final int BASE_TROPHY_CHANGE = 30;
    private static final int WIN_XP = 30;
    private static final int LOSS_XP = 10;
    private static final int DRAW_XP = 15;

    /** Trofeos: +30 si ganas, -30 si pierdes, 0 si empatas. */
    public int trophyChange(boolean won, boolean draw) {
        if (draw) return 0;
        return won ? BASE_TROPHY_CHANGE : -BASE_TROPHY_CHANGE;
    }

    /** XP: se gana siempre, aunque pierdas (incentiva seguir jugando). */
    public int experienceGained(boolean won, boolean draw) {
        if (draw) return DRAW_XP;
        return won ? WIN_XP : LOSS_XP;
    }
}