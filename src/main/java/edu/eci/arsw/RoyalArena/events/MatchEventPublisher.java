package edu.eci.arsw.RoyalArena.events;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Publica eventos de partida a RabbitMQ.
 *
 * En producción esto se resolvería con un outbox pattern: persistir el evento
 * en la BD dentro de la misma transacción y publicarlo con un proceso aparte
 * que reintenta. Vale la pena mencionarlo como trabajo futuro.
 */
@Slf4j
@Component
public class MatchEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String matchFinishedKey;
    private final String replayKey;

    public MatchEventPublisher(RabbitTemplate rabbitTemplate,
                               @Value("${royalarena.events.exchange}") String exchange,
                               @Value("${royalarena.events.routing-key.match-finished}") String matchFinishedKey,
                               @Value("${royalarena.events.routing-key.replay}") String replayKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.matchFinishedKey = matchFinishedKey;
        this.replayKey = replayKey;
    }

    public void publishMatchFinished(MatchFinishedEvent event) {
        try {
            rabbitTemplate.convertAndSend(exchange, matchFinishedKey, event);
            log.info("Published MatchFinishedEvent for match {} (winner: {})",
                    event.matchId(), event.winnerTeam() != null ? event.winnerTeam() : "DRAW");
        } catch (Exception e) {
            log.error("Failed to publish MatchFinishedEvent for match {}: {}",
                    event.matchId(), e.getMessage());
        }
    }


    public void publishReplay(ReplayPacket packet) {
        try {
            rabbitTemplate.convertAndSend(exchange, replayKey, packet);
            log.info("Published ReplayPacket for match {} ({} snapshots, {} cards)",
                    packet.matchId(), packet.snapshots().size(), packet.cardsPlayed().size());
        } catch (Exception e) {
            log.error("Failed to publish ReplayPacket for match {}: {}",
                    packet.matchId(), e.getMessage());
        }
    }
}