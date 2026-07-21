package edu.eci.arsw.RoyalArena.dto;

/**
 * Mensaje que se envía a un jugador cuando su acción es rechazada por el motor.
 * Se publica a /topic/match/{matchId}/errors/{playerId}.
 */
public record ActionErrorDTO(
        Long playerId,
        Long cardId,
        String reason
) { }