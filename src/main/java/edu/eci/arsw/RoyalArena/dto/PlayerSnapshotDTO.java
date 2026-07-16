package edu.eci.arsw.RoyalArena.dto;

import java.util.List;

public record PlayerSnapshotDTO(
        Long userId,
        String team,
        double elixir,
        List<Long> handCardIds,
        Long nextCardId
) { }