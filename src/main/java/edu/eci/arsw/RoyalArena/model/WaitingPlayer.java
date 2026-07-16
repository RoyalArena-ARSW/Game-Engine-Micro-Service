package edu.eci.arsw.RoyalArena.model;

import java.util.List;

/**
 * Jugador esperando partida en la cola de matchmaking.
 * El timestamp permite descartar entradas obsoletas (jugadores que cerraron
 * el navegador y quedaron "fantasma" en la cola).
 */
public record WaitingPlayer(Long userId,  List<CardSnapshot> deck, long queuedAtMs) { }