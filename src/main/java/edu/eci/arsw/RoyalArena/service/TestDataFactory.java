package edu.eci.arsw.RoyalArena.service;

import java.util.List;

import org.springframework.stereotype.Component;

import edu.eci.arsw.RoyalArena.model.CardSnapshot;

/**
 * Mazos de prueba hardcodeados para la Fase 1, mientras no integramos
 * con Deck-and-Cards (Fase 4). Stats aproximados de nivel torneo.
 */
@Component
public class TestDataFactory {

    public List<CardSnapshot> buildTestDeck() {
        return List.of(
            troop(1L, "Knight", 3, 202, 1766, 1.2, 1.0, 0.8, 1),
            troop(2L, "Archers", 3, 118, 304, 0.9, 1.0, 5.0, 2),
            troop(3L, "Giant", 5, 254, 4091, 1.5, 0.75, 0.8, 1),
            troop(4L, "Musketeer", 4, 218, 720, 1.0, 1.0, 6.0, 1),
            troop(5L, "Skeletons", 1, 81, 81, 1.0, 1.5, 0.8, 4),
            troop(6L, "Mini P.E.K.K.A", 4, 720, 1361, 1.6, 1.5, 0.8, 1),
            spell(7L, "Fireball", 4, 689, 2.5),
            spell(8L, "Arrows", 3, 366, 4.0)
        );
    }

    private CardSnapshot troop(Long id, String name, int elixir, int damage, int health,
                                double attackSpeed, double moveSpeed, double range, int count) {
        return CardSnapshot.builder()
                .cardId(id).name(name).type("TROOP").elixirCost(elixir)
                .damage(damage).health(health).isAerial(false)
                .attackSpeed(attackSpeed).movementSpeed(moveSpeed)
                .attackRange(range).target("GROUND").unitCount(count)
                .deploymentType("OWN_SIDE") 
                .build();
    }

    private CardSnapshot spell(Long id, String name, int elixir, int damage, double radius) {
        return CardSnapshot.builder()
                .cardId(id).name(name).type("SPELL").elixirCost(elixir)
                .damage(damage).effectRadius(radius)
                .deploymentType("ANYWHERE") 
                .build();
    }
}