package edu.eci.arsw.RoyalArena.dto.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Carta tal como la devuelve Deck-and-Cards. Es un DTO de FRONTERA: existe
 * solo para deserializar la respuesta del otro microservicio.
 *
 * Es plano (une los campos de Troop/Spell/Building) porque el JSON que llega
 * trae los campos del subtipo concreto; los que no apliquen llegan null.
 *
 * ignoreUnknown: si Deck-and-Cards agrega campos nuevos, Game Engine no se
 * rompe. Es lo que permite que los servicios evolucionen por separado.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteCardDTO(
        Long id,
        String name,
        String type,
        Integer elixirCost,
        String deploymentType,

        // Tropas y edificios
        Integer damage,
        Integer health,
        Boolean isAerial,
        Double attackSpeed,
        String movementSpeed,
        Double attackRange,
        String target,
        Integer unitCount,

        // Hechizos
        Double effectRadius,
        Double duration,

        // Edificios
        Integer lifetimeSeconds,
        Double selfDamagePerSecond
) { }