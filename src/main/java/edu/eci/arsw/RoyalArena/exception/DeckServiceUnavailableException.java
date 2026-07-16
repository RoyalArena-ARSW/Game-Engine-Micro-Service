package edu.eci.arsw.RoyalArena.exception;

/** Deck-and-Cards no respondió (caído, timeout, error 5xx). No es culpa del usuario. */
public class DeckServiceUnavailableException extends RuntimeException {
    public DeckServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}