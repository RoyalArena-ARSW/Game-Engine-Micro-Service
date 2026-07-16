package edu.eci.arsw.RoyalArena.model.records;

/**
 * Acción de un jugador pendiente de procesar: "quiero jugar la carta X
 * en la posición Y". Los jugadores NO mutan el estado directamente —
 * sus acciones se ENCOLAN y el game loop las procesa al inicio de cada
 * tick, dentro de su propio thread.
 *
 * Este es el patrón single-writer: un solo thread (el del tick) escribe
 * el estado de la partida; los threads de WebSocket solo encolan acciones
 * en una cola concurrente. Elimina las race conditions de raíz sin locks
 * sobre el estado del juego.
 */
public record PlayerAction(
        Long playerId,
        Long cardId,
        Position position,
        long timestampMs
) { }