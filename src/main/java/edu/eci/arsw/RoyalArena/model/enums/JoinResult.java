package edu.eci.arsw.RoyalArena.model.enums;

/**
 * Resultado de intentar entrar a la cola de matchmaking.
 */
public enum JoinResult {
    QUEUED,          // No había oponente: quedaste esperando
    MATCHED,         // Había oponente: partida creada, revisa tu topic
    ALREADY_QUEUED,   // Ya estabas en la cola
    NO_ACTIVE_DECK,            // No tienes mazo activo configurado
    DECK_SERVICE_UNAVAILABLE   // Deck-and-Cards no responde
}