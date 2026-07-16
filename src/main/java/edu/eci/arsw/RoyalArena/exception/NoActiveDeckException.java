package edu.eci.arsw.RoyalArena.exception;

/** El jugador no tiene un mazo activo configurado. Es culpa del usuario. */
public class NoActiveDeckException extends RuntimeException {
    public NoActiveDeckException(String message) {
        super(message);
    }
}