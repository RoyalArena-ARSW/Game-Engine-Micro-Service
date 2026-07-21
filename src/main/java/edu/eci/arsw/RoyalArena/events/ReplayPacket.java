package edu.eci.arsw.RoyalArena.events;

import java.util.List;

import edu.eci.arsw.RoyalArena.dto.MatchSnapshotDTO;

/**
 * Paquete completo de una partida terminada, listo para archivar como replay.
 * Se arma en memoria DURANTE la partida y se publica UNA vez al terminar, no
 * evento por evento: para las replays (que se ven después, no en vivo) basta
 * con enviar todo junto al final.
 *
 * Contiene lo elegido para reproducir: las cartas jugadas (ligeras, para el
 * timeline y efectos) y un snapshot del estado por segundo (para reconstruir el
 * tablero interpolando entre ellos, igual que el modo en vivo).
 */
public record ReplayPacket(
        String matchId,
        Long playerAId,
        String playerAName,
        Long playerBId,
        String playerBName,
        String winnerTeam,        // "TEAM_A" | "TEAM_B" | null (empate)
        int crownsA,
        int crownsB,
        double durationSeconds,
        long playedAtMs,
        List<ReplayCardPlayed> cardsPlayed,
        List<ReplaySnapshot> snapshots
) {
    /** Una carta jugada, con el momento (tick) en que ocurrió. */
    public record ReplayCardPlayed(
            int tick,
            Long playerId,
            Long cardId,
            double x,
            double y
    ) { }

    /** El estado del tablero en un instante, para reproducir. */
    public record ReplaySnapshot(
            int tick,
            MatchSnapshotDTO state
    ) { }
}