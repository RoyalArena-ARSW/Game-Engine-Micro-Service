package edu.eci.arsw.RoyalArena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot inmutable-en-la-práctica de los stats de una carta, cacheado
 * al iniciar la partida desde Deck-and-Cards. Durante los ticks NUNCA
 * se vuelve a consultar al otro microservicio: todo lo que el motor
 * necesita de la carta está aquí.
 *
 * Es una representación PROPIA de Game Engine (no se comparten clases
 * entre microservicios). Los campos son la unión de lo que necesitamos
 * de Troop/Spell/Building; los que no apliquen para un tipo quedan null.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardSnapshot {

    private Long cardId;
    private String name;
    /** TROOP, SPELL o BUILDING (como String para no acoplar enums entre servicios). */
    private String type;
    private int elixirCost;

    // ----- Stats de combate (troops y buildings) -----
    private Integer damage;
    private Integer health;
    private Boolean isAerial;
    private Double attackSpeed;       // segundos entre ataques
    private Double movementSpeed;     // tiles por segundo (ya convertido desde el enum)
    private Double attackRange;       // tiles
    private String target;            // GROUND, AIR_AND_GROUND, BUILDINGS_ONLY
    private Integer unitCount;        // cuántas unidades despliega (Skeletons=4)

    // ----- Stats de hechizos -----
    private Double effectRadius;
    private Double duration;

    // ----- Stats de estructuras -----
    private Integer lifetimeSeconds;
    private Double selfDamagePerSecond;
}