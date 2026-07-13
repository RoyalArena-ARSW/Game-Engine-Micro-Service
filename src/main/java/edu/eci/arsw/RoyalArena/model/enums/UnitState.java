package edu.eci.arsw.RoyalArena.model.enums;

/**
 * Estado de una unidad desplegada en el tablero. Lo actualiza el game loop
 * en cada tick según la situación de la unidad.
 */
public enum UnitState {
    MOVING,     // Avanzando hacia su objetivo
    ATTACKING,  // En rango de un objetivo, aplicando daño según su cooldown
    DEAD        // HP llegó a 0; el game loop la remueve en el siguiente tick
}