package edu.eci.arsw.RoyalArena.model.records;

/**
 * Posición continua sobre el tablero de 18x32 tiles.
 * x e y son double: una unidad puede estar en (9.3, 15.7), es decir,
 * dentro de la casilla (9, 15) pero desplazada. Esto permite movimiento
 * fluido (0.1 tiles por tick) y cálculo de distancias preciso para
 * los rangos de ataque.
 *
 * Al ser un record es INMUTABLE: moverse produce una Position nueva,
 * nunca se modifica una existente. Esto simplifica la concurrencia:
 * no hay estado mutable compartido a nivel de posición.
 */
public record Position(double x, double y) {

    /**
     * Distancia euclidiana a otra posición, en tiles.
     * Se usa para chequear rangos de ataque: una unidad con rango 5.5
     * puede atacar si distanceTo(objetivo) <= 5.5.
     */
    public double distanceTo(Position other) {
        double dx = other.x - this.x;
        double dy = other.y - this.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Devuelve una nueva posición avanzando hacia 'target' una distancia
     * de 'step' tiles (o menos, si el objetivo está más cerca que el paso).
     * Movimiento en línea recta — la física simplificada que acordamos.
     */
    public Position moveTowards(Position target, double step) {
        double distance = distanceTo(target);
        if (distance <= step || distance == 0) {
            return target;
        }
        double ratio = step / distance;
        return new Position(
                this.x + (target.x - this.x) * ratio,
                this.y + (target.y - this.y) * ratio
        );
    }
}