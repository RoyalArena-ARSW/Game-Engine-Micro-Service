package edu.eci.arsw.RoyalArena.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.eci.arsw.RoyalArena.model.enums.Team;
import edu.eci.arsw.RoyalArena.model.enums.UnitState;
import edu.eci.arsw.RoyalArena.model.records.Position;

class DeployedUnitTest {

    private CardSnapshot knight;
    private DeployedUnit unit;

    @BeforeEach
    void setUp() {
        knight = CardSnapshot.builder()
                .cardId(1L).name("Knight").type("TROOP").elixirCost(3)
                .damage(202).health(1766).isAerial(false)
                .attackSpeed(1.2).movementSpeed(1.0).attackRange(0.8)
                .target("GROUND").unitCount(1).deploymentType("OWN_SIDE")
                .build();
        unit = new DeployedUnit(knight, Team.TEAM_A, new Position(9.0, 5.0));
    }

    @Test
    @DisplayName("Nace con la vida de su carta y en estado MOVING")
    void startsAliveAndMoving() {
        assertThat(unit.getCurrentHealth()).isEqualTo(knight.getHealth());
        assertThat(unit.getState()).isEqualTo(UnitState.MOVING);
        assertThat(unit.isDead()).isFalse();
        assertThat(unit.getInstanceId()).isNotBlank();
    }

    @Test
    @DisplayName("Dos instancias de la misma carta tienen ids distintos")
    void instancesHaveUniqueIds() {
        DeployedUnit other = new DeployedUnit(knight, Team.TEAM_A, new Position(9.0, 5.0));
        assertThat(unit.getInstanceId()).isNotEqualTo(other.getInstanceId());
    }

    @Test
    @DisplayName("Al llegar a cero de vida, muere")
    void diesAtZeroHealth() {
        unit.applyDamage(knight.getHealth());
        assertThat(unit.getCurrentHealth()).isZero();
        assertThat(unit.getState()).isEqualTo(UnitState.DEAD);
        assertThat(unit.isDead()).isTrue();
    }

    @Test
    @DisplayName("Una unidad muerta no puede atacar")
    void deadUnitCannotAttack() {
        unit.applyDamage(knight.getHealth());
        assertThat(unit.canAttack()).isFalse();
    }

    @Test
    @DisplayName("Se mueve segun su velocidad y el delta del tick")
    void movesAccordingToSpeedAndDelta() {
        // velocidad 1.0 tiles/s, delta 0.1s → avanza 0.1 tiles
        unit.moveTowards(new Position(9.0, 10.0), 0.1);

        assertThat(unit.getPosition().y()).isCloseTo(5.1, Offset.offset(0.0001));
        assertThat(unit.getPosition().x()).isCloseTo(9.0, Offset.offset(0.0001));
    }

    @Test
    @DisplayName("No se pasa del objetivo si esta mas cerca que un paso")
    void doesNotOvershootTarget() {
        Position target = new Position(9.0, 5.05); // a 0.05, medio paso
        unit.moveTowards(target, 0.1);
        assertThat(unit.getPosition()).isEqualTo(target);
    }

    @Test
    @DisplayName("La ruta guarda el objetivo y la version de obstaculos")
    void pathTracksTargetAndVersion() {
        List<Position> waypoints = List.of(new Position(9.5, 6.5), new Position(9.5, 7.5));
        unit.setPath(waypoints, "TOWER:TEAM_B:KING", 3);

        assertThat(unit.getPath()).isEqualTo(waypoints);
        assertThat(unit.getPathTargetId()).isEqualTo("TOWER:TEAM_B:KING");
        assertThat(unit.getPathObstaclesVersion()).isEqualTo(3);
        assertThat(unit.getPathIndex()).isZero();
    }

    @Test
    @DisplayName("Asignar una ruta nueva reinicia el indice")
    void newPathResetsIndex() {
        unit.setPath(List.of(new Position(1, 1), new Position(2, 2)), "A", 0);
        unit.setPathIndex(1);

        unit.setPath(List.of(new Position(3, 3)), "B", 1);
        assertThat(unit.getPathIndex()).isZero();
    }
}