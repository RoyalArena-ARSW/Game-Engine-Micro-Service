package edu.eci.arsw.RoyalArena.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reglas de recompensa. Función pura: entra el resultado, sale el número.
 */
class RewardCalculatorTest {

    private RewardCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RewardCalculator();
    }

    @Test
    @DisplayName("Ganar suma trofeos, perder los resta")
    void trophyChangeIsSymmetric() {
        int win = calculator.trophyChange(true, false);
        int loss = calculator.trophyChange(false, false);

        assertThat(win).isPositive();
        assertThat(loss).isNegative();
        // Simétrico: lo que gana el vencedor es lo que pierde el vencido,
        // así el total de trofeos del sistema se conserva.
        assertThat(win).isEqualTo(-loss);
    }

    @Test
    @DisplayName("El empate no mueve trofeos")
    void drawGivesNoTrophies() {
        assertThat(calculator.trophyChange(false, true)).isZero();
    }

    @Test
    @DisplayName("El flag de empate manda sobre el de victoria")
    void drawTakesPrecedenceOverWon() {
        // Combinación que no debería ocurrir, pero si ocurre, empate manda
        assertThat(calculator.trophyChange(true, true)).isZero();
    }

    @Test
    @DisplayName("Siempre se gana XP, incluso perdiendo")
    void xpIsAlwaysPositive() {
        assertThat(calculator.experienceGained(true, false)).isPositive();
        assertThat(calculator.experienceGained(false, false)).isPositive();
        assertThat(calculator.experienceGained(false, true)).isPositive();
    }

    @Test
    @DisplayName("La XP premia mas ganar que empatar, y mas empatar que perder")
    void xpIsOrderedByOutcome() {
        int win = calculator.experienceGained(true, false);
        int draw = calculator.experienceGained(false, true);
        int loss = calculator.experienceGained(false, false);

        assertThat(win).isGreaterThan(draw);
        assertThat(draw).isGreaterThan(loss);
    }
}