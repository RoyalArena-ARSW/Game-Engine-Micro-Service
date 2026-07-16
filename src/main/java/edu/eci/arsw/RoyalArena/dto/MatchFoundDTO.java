package edu.eci.arsw.RoyalArena.dto;

/**
 * Notificación que recibe un jugador cuando el matchmaking lo empareja.
 * Se publica a /topic/matchmaking/{userId}.
 */
public record MatchFoundDTO(
        String matchId,
        Long opponentId,
        String yourTeam
) { }