package edu.eci.arsw.RoyalArena.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.TowerType;

/**
 * Estado de un jugador: elixir, ciclo de cartas y torres.
 *
 * Es una de las clases más críticas del motor: aquí vive la regla de elixir
 * (que determina el ritmo del juego) y el ciclo de cartas de Clash Royale.
 */
class PlayerStateTest {

    /** Mazo de 8 cartas con costes distintos, para probar el elixir. */
    private List<CardSnapshot> deckOf8() {
        List<CardSnapshot> deck = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            deck.add(CardSnapshot.builder()
                    .cardId(i)
                    .name("Card" + i)
                    .type("TROOP")
                    .elixirCost((int) i)   // costes 1..8
                    .damage(100)
                    .health(500)
                    .attackSpeed(1.0)
                    .movementSpeed(1.0)
                    .attackRange(1.0)
                    .target("GROUND")
                    .unitCount(1)
                    .deploymentType("OWN_SIDE")
                    .build());
        }
        return deck;
    }

    private PlayerState newPlayer() {
        return new PlayerState(1L, Team.TEAM_A, deckOf8());
    }

    // ===== Estado inicial =====

    @Test
    @DisplayName("Arranca con el elixir inicial y 4 cartas en mano")
    void startsWithInitialElixirAndFullHand() {
        PlayerState player = newPlayer();

        assertThat(player.getElixir()).isEqualTo(GameConstants.INITIAL_ELIXIR);
        assertThat(player.getHand()).hasSize(GameConstants.HAND_SIZE);
        assertThat(player.getCycleQueue()).hasSize(
                GameConstants.DECK_SIZE - GameConstants.HAND_SIZE);
    }

    @Test
    @DisplayName("Mano y ciclo juntos son el mazo completo, sin repetidos")
    void handAndQueueTogetherAreTheWholeDeck() {
        PlayerState player = newPlayer();

        List<Long> all = new ArrayList<>();
        player.getHand().forEach(c -> all.add(c.getCardId()));
        player.getCycleQueue().forEach(c -> all.add(c.getCardId()));

        assertThat(all).hasSize(GameConstants.DECK_SIZE);
        assertThat(all).doesNotHaveDuplicates();
        assertThat(all).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    @DisplayName("Crea 3 torres: un rey y dos princesas")
    void createsThreeTowers() {
        PlayerState player = newPlayer();

        assertThat(player.getTowers()).hasSize(3);
        assertThat(player.getKingTower().getType()).isEqualTo(TowerType.KING);
        assertThat(player.getTowers())
                .extracting(TowerState::getType)
                .containsExactlyInAnyOrder(
                        TowerType.KING, TowerType.PRINCESS_LEFT, TowerType.PRINCESS_RIGHT);
    }

    @Test
    @DisplayName("Cada equipo pone sus torres en su lado del tablero")
    void towersAreOnTheCorrectSide() {
        PlayerState teamA = new PlayerState(1L, Team.TEAM_A, deckOf8());
        PlayerState teamB = new PlayerState(2L, Team.TEAM_B, deckOf8());

        // TEAM_A abajo (y < río), TEAM_B arriba (y > río)
        assertThat(teamA.getTowers())
                .allMatch(t -> t.getPosition().y() < GameConstants.RIVER_Y_MIN);
        assertThat(teamB.getTowers())
                .allMatch(t -> t.getPosition().y() > GameConstants.RIVER_Y_MAX);
    }

    // ===== Elixir =====

    @Test
    @DisplayName("El elixir regenera segun el tiempo transcurrido")
    void elixirRegeneratesOverTime() {
        PlayerState player = newPlayer();
        double before = player.getElixir();

        player.regenerateElixir(1.0); // un segundo

        assertThat(player.getElixir())
                .isCloseTo(before + GameConstants.ELIXIR_PER_SECOND,
                        org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("El elixir nunca supera el maximo")
    void elixirIsCappedAtMax() {
        PlayerState player = newPlayer();

        player.regenerateElixir(1000.0); // una eternidad

        assertThat(player.getElixir()).isEqualTo(GameConstants.MAX_ELIXIR);
    }

    // ===== Ciclo de cartas =====

    @Test
    @DisplayName("Jugar una carta descuenta su coste de elixir")
    void playingACardCostsElixir() {
        PlayerState player = newPlayer();
        player.regenerateElixir(100.0); // elixir al tope

        CardSnapshot card = player.getHand().get(0);
        double before = player.getElixir();

        assertThat(player.tryPlayCard(card.getCardId())).isTrue();
        assertThat(player.getElixir()).isEqualTo(before - card.getElixirCost());
    }

    @Test
    @DisplayName("Sin elixir suficiente, la carta no se juega y nada cambia")
    void cannotPlayCardWithoutElixir() {
        PlayerState player = newPlayer();

        // Buscar en la mano una carta que cueste más que el elixir inicial (5)
        CardSnapshot expensive = player.getHand().stream()
                .filter(c -> c.getElixirCost() > GameConstants.INITIAL_ELIXIR)
                .findFirst()
                .orElse(null);

        // El mazo tiene costes 1..8; si el reparto no dejó una cara en mano,
        // el escenario no aplica.
        org.junit.jupiter.api.Assumptions.assumeTrue(expensive != null,
                "el reparto aleatorio no dejo una carta cara en la mano");

        double elixirBefore = player.getElixir();
        int handSizeBefore = player.getHand().size();

        assertThat(player.tryPlayCard(expensive.getCardId())).isFalse();
        assertThat(player.getElixir()).isEqualTo(elixirBefore);
        assertThat(player.getHand()).hasSize(handSizeBefore);
        assertThat(player.getHand()).contains(expensive);
    }

    @Test
    @DisplayName("No se puede jugar una carta que no esta en la mano")
    void cannotPlayCardNotInHand() {
        PlayerState player = newPlayer();
        player.regenerateElixir(100.0);

        CardSnapshot inQueue = player.getCycleQueue().peekFirst();
        assertThat(player.tryPlayCard(inQueue.getCardId())).isFalse();
    }

    @Test
    @DisplayName("Al jugar, la carta va al final del ciclo y entra la siguiente")
    void playingRotatesTheCycle() {
        PlayerState player = newPlayer();
        player.regenerateElixir(100.0);

        CardSnapshot played = player.getHand().get(0);
        CardSnapshot expectedNext = player.getCycleQueue().peekFirst();

        player.tryPlayCard(played.getCardId());

        // Sale de la mano, entra la que estaba primera en el ciclo
        assertThat(player.getHand()).doesNotContain(played);
        assertThat(player.getHand()).contains(expectedNext);
        assertThat(player.getHand()).hasSize(GameConstants.HAND_SIZE);

        // Y la jugada queda de última en el ciclo
        assertThat(player.getCycleQueue().peekLast()).isEqualTo(played);
    }

    @Test
    @DisplayName("El mazo completo cicla: tras 8 jugadas vuelve la primera carta")
    void deckCyclesCompletely() {
        PlayerState player = newPlayer();
        CardSnapshot first = player.getHand().get(0);

        List<Long> playedOrder = new ArrayList<>();
        for (int i = 0; i < GameConstants.DECK_SIZE; i++) {
            player.regenerateElixir(1000.0); // elixir infinito para el test
            CardSnapshot next = player.getHand().get(0);
            assertThat(player.tryPlayCard(next.getCardId())).isTrue();
            playedOrder.add(next.getCardId());
        }

        // Se jugaron las 8 cartas distintas, ninguna repetida
        assertThat(playedOrder).hasSize(GameConstants.DECK_SIZE);
        assertThat(playedOrder).doesNotHaveDuplicates();

        // Y la primera vuelve a estar disponible
        player.regenerateElixir(1000.0);
        assertThat(player.getHand()).contains(first);
    }

    @Test
    @DisplayName("findCardInDeck encuentra cualquier carta del mazo")
    void findsCardsInDeck() {
        PlayerState player = newPlayer();

        assertThat(player.findCardInDeck(1L)).isNotNull();
        assertThat(player.findCardInDeck(8L)).isNotNull();
        assertThat(player.findCardInDeck(999L)).isNull();
    }

    // ===== Torres =====

    @Test
    @DisplayName("Al inicio no hay torres destruidas")
    void noTowersDestroyedAtStart() {
        PlayerState player = newPlayer();

        assertThat(player.isKingTowerDestroyed()).isFalse();
        assertThat(player.countDestroyedTowers()).isZero();
    }

    @Test
    @DisplayName("Cuenta las torres destruidas y detecta la caida del rey")
    void tracksDestroyedTowers() {
        PlayerState player = newPlayer();

        TowerState princess = player.getTowers().stream()
                .filter(t -> t.getType() == TowerType.PRINCESS_LEFT)
                .findFirst().orElseThrow();
        princess.applyDamage(princess.getMaxHealth());

        assertThat(player.countDestroyedTowers()).isEqualTo(1);
        assertThat(player.isKingTowerDestroyed()).isFalse();

        TowerState king = player.getKingTower();
        king.applyDamage(king.getMaxHealth());

        assertThat(player.countDestroyedTowers()).isEqualTo(2);
        assertThat(player.isKingTowerDestroyed()).isTrue();
    }
}