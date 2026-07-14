package edu.eci.arsw.RoyalArena.model;

import java.util.UUID;

import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.UnitState;
import edu.eci.arsw.RoyalArena.model.records.Position;
import lombok.Getter;
import lombok.Setter;

/**
 * Una unidad VIVA desplegada en el tablero. Es la instancia de partida
 * de una carta del catálogo: la carta "Knight" es una sola en el sistema,
 * pero durante una partida puede haber 3 Knights desplegados, cada uno
 * con su propia posición, vida restante y objetivo.
 *
 * Mantiene una referencia al CardSnapshot con los stats base (daño,
 * velocidad, rango), y aquí solo vive el estado que CAMBIA durante
 * la partida.
 *
 * Thread-safety: igual que TowerState — solo el game loop escribe
 * (single-writer), otros threads leen snapshots. Campos mutables volatile.
 */
@Getter
public class DeployedUnit {

    private final String instanceId;
    private final CardSnapshot card;
    private final Team team;

    private volatile Position position;
    private volatile int currentHealth;
    private volatile UnitState state;

    /** instanceId de la unidad objetivo, o el identificador de una torre. Null si no tiene objetivo. */
    @Setter
    private volatile String targetId;

    /** Cooldown restante en ms para el próximo ataque. */
    private volatile double attackCooldownMs;

    public DeployedUnit(CardSnapshot card, Team team, Position spawnPosition) {
        this.instanceId = UUID.randomUUID().toString();
        this.card = card;
        this.team = team;
        this.position = spawnPosition;
        this.currentHealth = card.getHealth() != null ? card.getHealth() : 1;
        this.state = UnitState.MOVING;
        this.attackCooldownMs = 0;
    }

    /**
     * Mueve la unidad hacia el objetivo según su velocidad y el delta del tick.
     * Solo la invoca el game loop.
     */
    public void moveTowards(Position target, double deltaSeconds) {
        double speed = card.getMovementSpeed() != null ? card.getMovementSpeed() : 1.0;
        this.position = this.position.moveTowards(target, speed * deltaSeconds);
    }

    /**
     * Aplica daño. Si el HP llega a 0, la unidad pasa a DEAD y el game loop
     * la removerá del tablero en el siguiente tick.
     */
    public void applyDamage(int damage) {
        int newHealth = Math.max(0, this.currentHealth - damage);
        this.currentHealth = newHealth;
        if (newHealth == 0) {
            this.state = UnitState.DEAD;
        }
    }

    public void setState(UnitState state) {
        this.state = state;
    }

    public void setAttackCooldownMs(double cooldownMs) {
        this.attackCooldownMs = cooldownMs;
    }

    public void reduceCooldown(double deltaMs) {
        if (this.attackCooldownMs > 0) {
            this.attackCooldownMs = Math.max(0, this.attackCooldownMs - deltaMs);
        }
    }

    public boolean canAttack() {
        return state != UnitState.DEAD && attackCooldownMs <= 0;
    }

    public boolean isDead() {
        return state == UnitState.DEAD;
    }
}