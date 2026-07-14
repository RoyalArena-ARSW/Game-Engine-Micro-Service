package edu.eci.arsw.RoyalArena.dto;

/**
 * Foto de una unidad para enviar al cliente. Record inmutable:
 * se construye dentro del tick y se envía sin riesgo de que cambie
 * mientras se serializa.
 */
public record UnitSnapshotDTO(
        String instanceId,
        Long cardId,
        String cardName,
        String team,
        double x,
        double y,
        int currentHealth,
        int maxHealth,
        String state
) { }