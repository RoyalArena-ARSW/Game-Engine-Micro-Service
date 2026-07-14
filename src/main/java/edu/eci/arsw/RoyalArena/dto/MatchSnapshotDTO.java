package edu.eci.arsw.RoyalArena.dto;

import java.util.List;

/**
 * Foto completa del estado de una partida en un instante. Es lo que
 * el game loop emite después de cada tick (o cada N ticks) hacia los
 * clientes. Al ser records anidados, todo el snapshot es inmutable.
 */
public record MatchSnapshotDTO(
        String matchId,
        String status,
        double remainingSeconds,
        String winner,
        List<PlayerSnapshotDTO> players,
        List<TowerSnapshotDTO> towers,
        List<UnitSnapshotDTO> units
) { }