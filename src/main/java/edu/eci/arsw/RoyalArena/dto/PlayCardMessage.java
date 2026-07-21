package edu.eci.arsw.RoyalArena.dto;

/**
 * Mensaje que envía un cliente por WebSocket para jugar una carta.
 * Llega a /app/match/{matchId}/play.
 */
public record PlayCardMessage(
        Long playerId,
        Long cardId,
        double x,
        double y
) { }