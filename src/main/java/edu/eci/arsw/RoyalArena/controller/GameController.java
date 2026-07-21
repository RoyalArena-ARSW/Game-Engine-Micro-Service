package edu.eci.arsw.RoyalArena.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.eci.arsw.RoyalArena.dto.LiveMatchDTO;
import edu.eci.arsw.RoyalArena.dto.MatchSnapshotDTO;
import edu.eci.arsw.RoyalArena.model.GameMatch;
import edu.eci.arsw.RoyalArena.model.records.PlayerAction;
import edu.eci.arsw.RoyalArena.model.records.Position;
import edu.eci.arsw.RoyalArena.service.GameEngineService;
import edu.eci.arsw.RoyalArena.service.TestDataFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameController {

    private final GameEngineService gameEngine;
    private final TestDataFactory testDataFactory;

    /**
     * Crea una partida de PRUEBA con mazos hardcodeados y la arranca.
     */
    @PostMapping("/test-match")
    public ResponseEntity<Map<String, String>> createTestMatch(
            @RequestBody Map<String, Long> body) {
        Long userA = body.getOrDefault("userA", 1L);
        Long userB = body.getOrDefault("userB", 2L);

        GameMatch match = gameEngine.createMatch(
                userA, testDataFactory.buildTestDeck(),
                userB, testDataFactory.buildTestDeck());
        gameEngine.startMatch(match.getMatchId());

        return new ResponseEntity<>(Map.of("matchId", match.getMatchId()), HttpStatus.CREATED);
    }

    /**
     * Juega una carta. Body: {playerId, cardId, x, y}.
     * La acción se ENCOLA; el game loop la procesa en su próximo tick.
     */
    @PostMapping("/{matchId}/play")
    public ResponseEntity<Map<String, String>> playCard(
            @PathVariable String matchId,
            @RequestBody Map<String, Object> body) {

        Long playerId = Long.valueOf(body.get("playerId").toString());
        Long cardId = Long.valueOf(body.get("cardId").toString());
        double x = Double.parseDouble(body.get("x").toString());
        double y = Double.parseDouble(body.get("y").toString());

        gameEngine.submitAction(matchId, new PlayerAction(
                playerId, cardId, new Position(x, y), System.currentTimeMillis()));

        return ResponseEntity.ok(Map.of("status", "action queued"));
    }

    /**
     * Foto del estado actual de la partida.
     */
    @GetMapping("/{matchId}")
    public ResponseEntity<MatchSnapshotDTO> getMatchState(@PathVariable String matchId) {
        return ResponseEntity.ok(gameEngine.buildSnapshot(matchId));
    }
    /**
     * Lista de partidas en vivo para la TV Royale. Público: cualquiera puede
     * ver qué se está jugando.
     */
    @GetMapping("/api/games/live")
    public ResponseEntity<List<LiveMatchDTO>> getLiveMatches() {
        return ResponseEntity.ok(gameEngine.getActiveMatches());
    }
}