package edu.eci.arsw.RoyalArena.model;

/**
 * Jugador esperando partida en la cola de matchmaking.
 * El timestamp permite descartar entradas obsoletas (jugadores que cerraron
 * el navegador y quedaron "fantasma" en la cola).
 */
public record WaitingPlayer(Long userId, long queuedAtMs) { }