package edu.eci.arsw.RoyalArena.model.enums;

/**
 * Ciclo de vida de una partida.
 */
public enum MatchStatus {
    WAITING,      // Creada, esperando que ambos jugadores confirmen conexión
    IN_PROGRESS,  // Game loop activo
    FINISHED      // Terminada (por victoria o tiempo). Pendiente de limpieza.
}