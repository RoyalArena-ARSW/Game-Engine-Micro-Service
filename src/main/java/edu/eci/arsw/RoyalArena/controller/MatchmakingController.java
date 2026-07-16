package edu.eci.arsw.RoyalArena.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.eci.arsw.RoyalArena.model.enums.JoinResult;
import edu.eci.arsw.RoyalArena.service.MatchmakingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoints de matchmaking.
 *
 * Nota: por ahora el userId viene en el body porque el cliente de prueba llama
 * directo a Game Engine. Cuando se integre con el Gateway, pasará a leerse del
 * header X-User-Id (que el Gateway inyecta tras validar el JWT), igual que en
 * los demás microservicios.
 */
@Slf4j
@RestController
@RequestMapping("/api/matchmaking")
@RequiredArgsConstructor
public class MatchmakingController {

    private final MatchmakingService matchmaking;

    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> join(@RequestBody Map<String, Long> body) {
        Long userId = body.get("userId");
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        JoinResult result = matchmaking.joinQueue(userId);
        return ResponseEntity.ok(Map.of(
                "result", result.name(),
                "queueSize", matchmaking.getQueueSize()));
    }

    @PostMapping("/leave")
    public ResponseEntity<Map<String, Object>> leave(@RequestBody Map<String, Long> body) {
        Long userId = body.get("userId");
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        boolean removed = matchmaking.leaveQueue(userId);
        return ResponseEntity.ok(Map.of(
                "removed", removed,
                "queueSize", matchmaking.getQueueSize()));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("queueSize", matchmaking.getQueueSize()));
    }
}