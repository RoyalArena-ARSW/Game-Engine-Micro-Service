package edu.eci.arsw.RoyalArena.dto;

public record TowerSnapshotDTO(
        String type,
        String team,
        double x,
        double y,
        int currentHealth,
        int maxHealth,
        boolean destroyed
) { }