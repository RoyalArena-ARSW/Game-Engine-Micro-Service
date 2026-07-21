package edu.eci.arsw.RoyalArena.events;

import java.util.List;

/**
 * Evento publicado cuando una partida termina. Contrato entre Game Engine
 * (productor) y los consumidores (Profile ahora; Replay y Broadcast después).
 *
 * Se duplica en cada microservicio a propósito: compartir una librería de
 * clases acoplaría los servicios en tiempo de compilación. Lo compartido es
 * el formato del mensaje, no el código.
 *
 * Es autocontenido: trae todo lo que un consumidor necesita, sin tener que
 * volver a preguntarle nada a Game Engine.
 */
public record MatchFinishedEvent(
        String matchId,
        String winnerTeam,          // "TEAM_A", "TEAM_B" o null si empate
        double durationSeconds,
        long finishedAtMs,
        List<PlayerResult> players
) {
    /**
     * Resultado de un jugador. El trophyChange lo calcula Game Engine para
     * que los consumidores no tengan que replicar la regla.
     */
    public record PlayerResult(
            Long userId,
            String team,
            boolean won,
            boolean draw,
            int crownsEarned,        // torres enemigas que destruyó (0-3)
            int crownsConceded,      // torres propias que perdió (0-3)
            boolean threeCrownWin,
            int trophyChange,
            int experienceGained
    ) { }
}