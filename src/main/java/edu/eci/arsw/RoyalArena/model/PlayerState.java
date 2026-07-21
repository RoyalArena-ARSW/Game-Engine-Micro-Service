package edu.eci.arsw.RoyalArena.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.TowerType;
import edu.eci.arsw.RoyalArena.model.records.Position;
import lombok.Getter;

/**
 * Estado de un jugador dentro de una partida: su elixir, sus torres,
 * su mazo y el ciclo de cartas en mano.
 *
 * El ciclo de cartas funciona como en Clash Royale: de las 8 cartas,
 * 4 están "en mano" y el resto en cola. Al jugar una carta, sale de la
 * mano, entra la siguiente de la cola, y la jugada va al FINAL de la cola.
 *
 * Thread-safety: mismo patrón single-writer. El elixir es volatile porque
 * es double y queremos visibilidad entre el thread del loop y los lectores.
 */
@Getter
public class PlayerState {

    private final Long userId;
    private final Team team;
    private final List<CardSnapshot> deck;
    private final List<TowerState> towers;

    /** Cartas actualmente en mano (jugables). */
    private final List<CardSnapshot> hand;
    /** Cola del ciclo: la primera es la "próxima carta". */
    private final Deque<CardSnapshot> cycleQueue;

    private volatile double elixir;

    public PlayerState(Long userId, Team team, List<CardSnapshot> deck) {
        this.userId = userId;
        this.team = team;
        this.deck = List.copyOf(deck);
        this.elixir = GameConstants.INITIAL_ELIXIR;

        // Barajar el mazo y repartir: 4 a la mano, 4 a la cola de ciclo.
        List<CardSnapshot> shuffled = new ArrayList<>(deck);
        Collections.shuffle(shuffled);
        this.hand = new ArrayList<>(shuffled.subList(0, GameConstants.HAND_SIZE));
        this.cycleQueue = new ArrayDeque<>(shuffled.subList(GameConstants.HAND_SIZE, shuffled.size()));

        // Crear las 3 torres según el lado del tablero.
        this.towers = createTowers(team);
    }

    /**
     * Regenera elixir según el tiempo transcurrido. Solo la invoca el game loop.
     */
    public void regenerateElixir(double deltaSeconds) {
        double newElixir = Math.min(GameConstants.MAX_ELIXIR,
                this.elixir + GameConstants.ELIXIR_PER_SECOND * deltaSeconds);
        this.elixir = newElixir;
    }

    /**
     * Intenta jugar una carta de la mano. Si hay elixir suficiente y la carta
     * está en mano: descuenta elixir, rota el ciclo y devuelve true.
     * Solo la invoca el game loop (las acciones de los jugadores se encolan
     * y se procesan dentro del tick — así nunca hay dos threads mutando esto).
     */
    public boolean tryPlayCard(Long cardId) {
        CardSnapshot cardInHand = hand.stream()
                .filter(c -> c.getCardId().equals(cardId))
                .findFirst()
                .orElse(null);

        if (cardInHand == null || elixir < cardInHand.getElixirCost()) {
            return false;
        }

        this.elixir -= cardInHand.getElixirCost();
        hand.remove(cardInHand);
        CardSnapshot next = cycleQueue.pollFirst();
        if (next != null) {
            hand.add(next);
        }
        cycleQueue.addLast(cardInHand);
        return true;
    }

    /**
     * Busca en el mazo la carta por id (para obtener sus stats al desplegar).
     */
    public CardSnapshot findCardInDeck(Long cardId) {
        return deck.stream()
                .filter(c -> c.getCardId().equals(cardId))
                .findFirst()
                .orElse(null);
    }

    public TowerState getKingTower() {
        return towers.stream()
                .filter(t -> t.getType() == TowerType.KING)
                .findFirst()
                .orElseThrow();
    }

    public boolean isKingTowerDestroyed() {
        return getKingTower().isDestroyed();
    }

    public long countDestroyedTowers() {
        return towers.stream().filter(TowerState::isDestroyed).count();
    }

    private List<TowerState> createTowers(Team team) {
        List<TowerState> result = new ArrayList<>();
        if (team == Team.TEAM_A) {
            result.add(new TowerState(TowerType.KING, team,
                    new Position(GameConstants.KING_A_X, GameConstants.KING_A_Y),
                    GameConstants.KING_TOWER_HP));
            result.add(new TowerState(TowerType.PRINCESS_LEFT, team,
                    new Position(GameConstants.PRINCESS_A_LEFT_X, GameConstants.PRINCESS_A_Y),
                    GameConstants.PRINCESS_TOWER_HP));
            result.add(new TowerState(TowerType.PRINCESS_RIGHT, team,
                    new Position(GameConstants.PRINCESS_A_RIGHT_X, GameConstants.PRINCESS_A_Y),
                    GameConstants.PRINCESS_TOWER_HP));
        } else {
            result.add(new TowerState(TowerType.KING, team,
                    new Position(GameConstants.KING_B_X, GameConstants.KING_B_Y),
                    GameConstants.KING_TOWER_HP));
            result.add(new TowerState(TowerType.PRINCESS_LEFT, team,
                    new Position(GameConstants.PRINCESS_B_LEFT_X, GameConstants.PRINCESS_B_Y),
                    GameConstants.PRINCESS_TOWER_HP));
            result.add(new TowerState(TowerType.PRINCESS_RIGHT, team,
                    new Position(GameConstants.PRINCESS_B_RIGHT_X, GameConstants.PRINCESS_B_Y),
                    GameConstants.PRINCESS_TOWER_HP));
        }
        return result;
    }
}