package edu.eci.arsw.RoyalArena.client;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import edu.eci.arsw.RoyalArena.dto.remote.RemoteCardDTO;
import edu.eci.arsw.RoyalArena.dto.remote.RemoteDeckDTO;
import edu.eci.arsw.RoyalArena.exception.DeckServiceUnavailableException;
import edu.eci.arsw.RoyalArena.exception.NoActiveDeckException;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;

import lombok.extern.slf4j.Slf4j;

/**
 * Cliente REST hacia Deck-and-Cards. Es la ÚNICA clase de Game Engine que
 * sabe que ese microservicio existe: actúa como anti-corruption layer y
 * traduce el modelo ajeno (RemoteCardDTO) al propio (CardSnapshot).
 *
 * Se llama SOLO desde threads de request (Tomcat), NUNCA desde el game loop:
 * el loop debe correr sin I/O de red. Por eso el .block() es aceptable aquí.
 *
 * El timeout es obligatorio: sin él, un Deck-and-Cards colgado bloquearía
 * indefinidamente el thread que pide partida.
 */
@Slf4j
@Component
public class DeckAndCardsClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;

    public DeckAndCardsClient(WebClient.Builder builder,
                              @Value("${services.deck-and-cards.url}") String baseUrl,
                              @Value("${services.internal-secret}") String internalSecret) {
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .build();
        log.info("DeckAndCardsClient configured against {}", baseUrl);
    }

    /**
     * Trae el mazo activo de un usuario y lo traduce a CardSnapshots.
     *
     * @throws NoActiveDeckException          si el usuario no tiene mazo activo (404)
     * @throws DeckServiceUnavailableException si el servicio no responde
     */
    public List<CardSnapshot> fetchActiveDeck(Long userId) {
        RemoteDeckDTO deck;
        try {
            deck = webClient.get()
                    .uri("/api/decks/user/{userId}/active", userId)
                    .retrieve()
                    .bodyToMono(RemoteDeckDTO.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            throw new NoActiveDeckException("User " + userId + " has no active deck");
        } catch (Exception e) {
            throw new DeckServiceUnavailableException(
                    "Could not fetch deck for user " + userId + ": " + e.getMessage(), e);
        }

        if (deck == null || deck.cards() == null || deck.cards().isEmpty()) {
            throw new NoActiveDeckException("Active deck for user " + userId + " is empty");
        }

        List<CardSnapshot> snapshots = deck.cards().stream().map(this::toSnapshot).toList();
        log.info("Fetched active deck '{}' for user {} ({} cards)",
                deck.name(), userId, snapshots.size());
        return snapshots;
    }

    /**
     * Traduce la carta del catálogo al snapshot que usa el motor.
     */
    private CardSnapshot toSnapshot(RemoteCardDTO dto) {
        return CardSnapshot.builder()
                .cardId(dto.id())
                .name(dto.name())
                .type(dto.type())
                .elixirCost(dto.elixirCost() != null ? dto.elixirCost() : 0)
                .deploymentType(dto.deploymentType())
                .damage(dto.damage())
                .health(dto.health())
                .isAerial(dto.isAerial())
                .attackSpeed(dto.attackSpeed())
                .movementSpeed(toTilesPerSecond(dto.movementSpeed()))
                .attackRange(dto.attackRange())
                .target(dto.target())
                .unitCount(dto.unitCount())
                .effectRadius(dto.effectRadius())
                .duration(dto.duration())
                .lifetimeSeconds(dto.lifetimeSeconds())
                .selfDamagePerSecond(dto.selfDamagePerSecond())
                .build();
    }

    /**
     * Deck-and-Cards modela la velocidad como CATEGORÍA (enum); el motor la
     * necesita como MAGNITUD (tiles por segundo). Esta traducción es la razón
     * de ser del cliente: cada servicio expresa el dominio a su manera y aquí
     * se hace el puente.
     */
    private Double toTilesPerSecond(String movementSpeed) {
        if (movementSpeed == null) {
            return 1.0;
        }
        return switch (movementSpeed) {
            case "SLOW" -> 0.75;
            case "MEDIUM" -> 1.0;
            case "FAST" -> 1.5;
            case "VERY_FAST" -> 2.0;
            default -> {
                log.warn("Unknown movementSpeed '{}', defaulting to 1.0", movementSpeed);
                yield 1.0;
            }
        };
    }
}