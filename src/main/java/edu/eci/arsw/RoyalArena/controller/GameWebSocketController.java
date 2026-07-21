package edu.eci.arsw.RoyalArena.controller;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import edu.eci.arsw.RoyalArena.dto.PlayCardMessage;
import edu.eci.arsw.RoyalArena.model.records.PlayerAction;
import edu.eci.arsw.RoyalArena.model.records.Position;
import edu.eci.arsw.RoyalArena.service.GameEngineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recibe las acciones de los jugadores por WebSocket (STOMP).
 *
 * Cuando un cliente envía a /app/match/{matchId}/play, este método se
 * ejecuta, encola la acción en el motor (igual que hacía el REST),
 * y el game loop la procesa en su próximo tick.
 *
 * Nota: encolar es lo único que hace. NO muta el estado (patrón
 * single-writer). El thread de WebSocket solo deposita la acción en la
 * cola concurrente de la partida.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class GameWebSocketController {

    private final GameEngineService gameEngine;

    @MessageMapping("/match/{matchId}/play")
    public void playCard(@DestinationVariable String matchId,
                         @Payload PlayCardMessage message) {
        log.debug("WS play: match={} player={} card={} at ({},{})",
                matchId, message.playerId(), message.cardId(), message.x(), message.y());

        gameEngine.submitAction(matchId, new PlayerAction(
                message.playerId(),
                message.cardId(),
                new Position(message.x(), message.y()),
                System.currentTimeMillis()));
    }
}