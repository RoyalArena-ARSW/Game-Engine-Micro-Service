package edu.eci.arsw.RoyalArena.dto;

/**
 * Resumen de una partida en curso, para la lista de la TV Royale. No lleva el
 * estado completo (unidades, torres): solo lo necesario para que un espectador
 * elija qué partida ver. El estado completo lo recibe al suscribirse al topic.
 */
public record LiveMatchDTO(
        String matchId,
        Long playerAId,
        String playerAName,
        Long playerBId,
        String playerBName,
        double remainingSeconds,
        int crownsA,          // torres del rival que A destruyó
        int crownsB
) { }