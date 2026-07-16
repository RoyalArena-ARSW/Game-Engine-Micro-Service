package edu.eci.arsw.RoyalArena.model.enums;

/**
 * Equipos de una partida. Se usa TEAM_A/TEAM_B (y no PLAYER_1/PLAYER_2)
 * para dejar abierta la extensión a 2v2: en ese modo cada equipo tendrá
 * dos jugadores, pero el concepto de "lado del tablero" no cambia.
 * TEAM_A juega desde la parte inferior (y bajas), TEAM_B desde la superior.
 */
public enum Team {
    TEAM_A,
    TEAM_B;

    /**
     * Devuelve el equipo contrario. Útil para buscar objetivos enemigos.
     */
    public Team opposite() {
        return this == TEAM_A ? TEAM_B : TEAM_A;
    }
}