package edu.eci.arsw.RoyalArena.model;

import java.util.ArrayList;
import java.util.List;

import edu.eci.arsw.RoyalArena.dto.MatchSnapshotDTO;
import edu.eci.arsw.RoyalArena.events.ReplayPacket;

/**
 * Acumula los datos de replay de UNA partida mientras se juega. Vive dentro del
 * GameMatch. Solo lo escribe el tick de esa partida (single-writer, sin locks).
 *
 * Guarda cada carta jugada, y un snapshot del estado cada 'snapshotEveryTicks'
 * ticks (no en cada tick: eso sería demasiado pesado; uno por segundo basta para
 * reproducir interpolando).
 */
public class ReplayRecorder {

    private final List<ReplayPacket.ReplayCardPlayed> cardsPlayed = new ArrayList<>();
    private final List<ReplayPacket.ReplaySnapshot> snapshots = new ArrayList<>();
    private final int snapshotEveryTicks;

    private int tickCounter = 0;

    public ReplayRecorder(int snapshotEveryTicks) {
        this.snapshotEveryTicks = snapshotEveryTicks;
    }

    /** Avanza el contador de ticks. Se llama una vez por tick. */
    public void onTick() {
        tickCounter++;
    }

    public int getCurrentTick() {
        return tickCounter;
    }

    /** Registra una carta jugada en el tick actual. */
    public void recordCardPlayed(Long playerId, Long cardId, double x, double y) {
        cardsPlayed.add(new ReplayPacket.ReplayCardPlayed(
                tickCounter, playerId, cardId, x, y));
    }

    /**
     * Guarda un snapshot si toca (cada snapshotEveryTicks). El GameEngineService
     * le pasa el snapshot ya construido para no volver a armarlo.
     */
    public void maybeRecordSnapshot(MatchSnapshotDTO state) {
        if(snapshotEveryTicks != 0) {
            if (tickCounter % snapshotEveryTicks == 0) {
                snapshots.add(new ReplayPacket.ReplaySnapshot(tickCounter, state));
            }
        }
    }

    public List<ReplayPacket.ReplayCardPlayed> getCardsPlayed() {
        return cardsPlayed;
    }

    public List<ReplayPacket.ReplaySnapshot> getSnapshots() {
        return snapshots;
    }
}