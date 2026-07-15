package edu.eci.arsw.RoyalArena.model;

/**
 * Celda discreta del tablero (columna, fila). El tablero de posiciones
 * continuas (18x32 tiles) se discretiza en celdas para el pathfinding:
 * una posición (9.3, 15.7) pertenece a la celda (9, 15).
 */
public record Cell(int col, int row) { }