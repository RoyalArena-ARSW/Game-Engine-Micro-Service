package edu.eci.arsw.RoyalArena.dto.remote;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteDeckDTO(
        Long id,
        String name,
        Long userId,
        Boolean active,
        List<RemoteCardDTO> cards
) { }