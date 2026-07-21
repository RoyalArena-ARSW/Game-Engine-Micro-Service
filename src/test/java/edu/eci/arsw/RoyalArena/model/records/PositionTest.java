package edu.eci.arsw.RoyalArena.model.records;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PositionTest {

    private static final Offset<Double> EPS = Offset.offset(0.0001);

    @Test
    @DisplayName("La distancia es euclidiana")
    void computesEuclideanDistance() {
        Position a = new Position(0, 0);
        Position b = new Position(3, 4);
        assertThat(a.distanceTo(b)).isCloseTo(5.0, EPS); // 3-4-5
    }

    @Test
    @DisplayName("La distancia es simetrica y a si mismo es cero")
    void distanceIsSymmetricAndZeroToSelf() {
        Position a = new Position(2.5, 7.3);
        Position b = new Position(9.1, 1.2);

        assertThat(a.distanceTo(b)).isCloseTo(b.distanceTo(a), EPS);
        assertThat(a.distanceTo(a)).isZero();
    }

    @Test
    @DisplayName("moveTowards avanza exactamente el paso indicado")
    void movesExactlyOneStep() {
        Position from = new Position(0, 0);
        Position target = new Position(10, 0);

        Position moved = from.moveTowards(target, 2.0);

        assertThat(moved.x()).isCloseTo(2.0, EPS);
        assertThat(from.distanceTo(moved)).isCloseTo(2.0, EPS);
    }

    @Test
    @DisplayName("moveTowards no se pasa del objetivo")
    void doesNotOvershoot() {
        Position from = new Position(0, 0);
        Position target = new Position(1, 0);

        assertThat(from.moveTowards(target, 5.0)).isEqualTo(target);
    }

    @Test
    @DisplayName("moveTowards hacia si mismo no rompe (division por cero)")
    void handlesZeroDistance() {
        Position p = new Position(5, 5);
        assertThat(p.moveTowards(p, 1.0)).isEqualTo(p);
    }

    @Test
    @DisplayName("Position es inmutable: moverse devuelve una instancia nueva")
    void isImmutable() {
        Position original = new Position(0, 0);
        original.moveTowards(new Position(10, 10), 1.0);

        assertThat(original.x()).isZero();
        assertThat(original.y()).isZero();
    }
}