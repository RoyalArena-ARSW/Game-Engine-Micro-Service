package edu.eci.arsw.RoyalArena.model;

import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.TowerType;
import edu.eci.arsw.RoyalArena.model.records.Position;
import lombok.Getter;

/**
 * Estado de una torre durante la partida. Las torres son estáticas
 * (no se mueven) pero atacan a unidades enemigas en rango y pueden
 * ser destruidas.
 *
 * Thread-safety: los campos mutables solo los escribe el thread del
 * game loop (patrón single-writer que aplicaremos en el GameEngineService),
 * pero pueden ser leídos desde otros threads (snapshots para WebSocket).
 * Por eso currentHealth y destroyed son volatile.
 */
@Getter
public class TowerState {

    private final TowerType type;
    private final Team team;
    private final Position position;
    private final int maxHealth;

    private volatile int currentHealth;
    private volatile boolean destroyed;

    /** Cooldown restante (en ms) para el próximo ataque de la torre. */
    private volatile double attackCooldownMs;

    public TowerState(TowerType type, Team team, Position position, int maxHealth) {
        this.type = type;
        this.team = team;
        this.position = position;
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
        this.destroyed = false;
        this.attackCooldownMs = 0;
    }

    /**
     * Aplica daño a la torre. Solo debe invocarse desde el thread del game loop.
     */
    public void applyDamage(int damage) {
        int newHealth = Math.max(0, this.currentHealth - damage);
        this.currentHealth = newHealth;
        if (newHealth == 0) {
            this.destroyed = true;
        }
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
        return !destroyed && attackCooldownMs <= 0;
    }
}