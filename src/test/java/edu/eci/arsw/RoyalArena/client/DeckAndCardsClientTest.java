package edu.eci.arsw.RoyalArena.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import edu.eci.arsw.RoyalArena.exception.DeckServiceUnavailableException;
import edu.eci.arsw.RoyalArena.exception.NoActiveDeckException;
import edu.eci.arsw.RoyalArena.model.CardSnapshot;
import reactor.core.publisher.Mono;

/**
 * El cliente hacia Deck-and-Cards.
 *
 * Es la única clase de Game Engine que sabe que ese microservicio existe:
 * traduce el modelo ajeno al propio. Lo que se prueba aquí es esa TRADUCCIÓN
 * y el manejo de fallas — no la red.
 *
 * En vez de levantar un servidor, se stubbea el ExchangeFunction del WebClient:
 * respuestas prefabricadas, cero I/O, tests en milisegundos.
 */
class DeckAndCardsClientTest {

    private static final Offset<Double> EPS = Offset.offset(0.001);

    /** Construye el cliente con una respuesta HTTP prefabricada. */
    private DeckAndCardsClient clientReturning(HttpStatus status, String jsonBody) {
        ExchangeFunction stub = request -> Mono.just(
                ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(jsonBody)
                        .build());
        return new DeckAndCardsClient(
                WebClient.builder().exchangeFunction(stub),
                "http://localhost:8081",
                "royalarena-internal-secret-dev");
    }

    /** Un mazo de una sola carta, con la velocidad que le pasemos. */
    private String deckJsonWithSpeed(String movementSpeed) {
        return """
            {
              "id": 1,
              "name": "Ciclo Rapido",
              "userId": 1,
              "isActive": true,
              "cards": [
                {
                  "id": 1,
                  "name": "Knight",
                  "type": "TROOP",
                  "elixirCost": 3,
                  "deploymentType": "OWN_SIDE",
                  "damage": 202,
                  "health": 1766,
                  "isAerial": false,
                  "attackSpeed": 1.2,
                  "movementSpeed": "%s",
                  "attackRange": 0.0,
                  "target": "GROUND",
                  "unitCount": 1
                }
              ]
            }
            """.formatted(movementSpeed);
    }

    // ===== Camino feliz =====

    @Test
    @DisplayName("Traduce la carta del catalogo al CardSnapshot del motor")
    void translatesCardToSnapshot() {
        DeckAndCardsClient client = clientReturning(HttpStatus.OK, deckJsonWithSpeed("MEDIUM"));

        List<CardSnapshot> deck = client.fetchActiveDeck(1L);

        assertThat(deck).hasSize(1);
        CardSnapshot knight = deck.get(0);
        assertThat(knight.getCardId()).isEqualTo(1L);
        assertThat(knight.getName()).isEqualTo("Knight");
        assertThat(knight.getType()).isEqualTo("TROOP");
        assertThat(knight.getDamage()).isEqualTo(202);
        assertThat(knight.getHealth()).isEqualTo(1766);
        assertThat(knight.getTarget()).isEqualTo("GROUND");
        assertThat(knight.getDeploymentType()).isEqualTo("OWN_SIDE");
    }

    /**
     * ESTA es la razón de ser del cliente: Deck-and-Cards modela la velocidad
     * como CATEGORÍA (un enum), el motor la necesita como MAGNITUD (tiles por
     * segundo). Cada servicio expresa el dominio a su manera y aquí se hace el
     * puente.
     */
    @Test
    @DisplayName("Traduce el enum MovementSpeed a tiles por segundo")
    void translatesMovementSpeedEnumToTilesPerSecond() {
        assertThat(clientReturning(HttpStatus.OK, deckJsonWithSpeed("SLOW"))
                .fetchActiveDeck(1L).get(0).getMovementSpeed()).isCloseTo(0.75, EPS);
        assertThat(clientReturning(HttpStatus.OK, deckJsonWithSpeed("MEDIUM"))
                .fetchActiveDeck(1L).get(0).getMovementSpeed()).isCloseTo(1.0, EPS);
        assertThat(clientReturning(HttpStatus.OK, deckJsonWithSpeed("FAST"))
                .fetchActiveDeck(1L).get(0).getMovementSpeed()).isCloseTo(1.5, EPS);
        assertThat(clientReturning(HttpStatus.OK, deckJsonWithSpeed("VERY_FAST"))
                .fetchActiveDeck(1L).get(0).getMovementSpeed()).isCloseTo(2.0, EPS);
    }

    /**
     * Si Deck-and-Cards agrega un valor al enum, una carta desconocida NO debe
     * tumbar la partida: se loguea y se usa el default.
     */
    @Test
    @DisplayName("Una velocidad desconocida cae al default en vez de reventar")
    void unknownMovementSpeedFallsBackToDefault() {
        DeckAndCardsClient client = clientReturning(
                HttpStatus.OK, deckJsonWithSpeed("SUPERSONICA"));

        assertThat(client.fetchActiveDeck(1L).get(0).getMovementSpeed())
                .isCloseTo(1.0, EPS);
    }

    @Test
    @DisplayName("Campos desconocidos en el JSON no rompen la deserializacion")
    void unknownJsonFieldsAreIgnored() {
        // Simula que Deck-and-Cards agregó campos nuevos que Game Engine no conoce.
        // Sin @JsonIgnoreProperties, esto reventaría y los servicios no podrían
        // evolucionar por separado.
        String withExtras = """
            {
              "id": 1, "name": "Test", "userId": 1, "isActive": true,
              "campoNuevoQueNoConozco": "algo",
              "cards": [
                {
                  "id": 1, "name": "Knight", "type": "TROOP", "elixirCost": 3,
                  "deploymentType": "OWN_SIDE", "movementSpeed": "MEDIUM",
                  "otroCampoNuevo": 42
                }
              ]
            }
            """;

        DeckAndCardsClient client = clientReturning(HttpStatus.OK, withExtras);

        assertThat(client.fetchActiveDeck(1L)).hasSize(1);
    }

    @Test
    @DisplayName("Manda el X-Internal-Secret y pega al endpoint correcto")
    void sendsInternalSecretToTheRightEndpoint() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction stub = request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(deckJsonWithSpeed("MEDIUM"))
                    .build());
        };
        DeckAndCardsClient client = new DeckAndCardsClient(
                WebClient.builder().exchangeFunction(stub),
                "http://localhost:8081",
                "royalarena-internal-secret-dev");

        client.fetchActiveDeck(7L);

        assertThat(captured.get().url().toString())
                .isEqualTo("http://localhost:8081/api/decks/user/7/active");
        assertThat(captured.get().headers().getFirst("X-Internal-Secret"))
                .isEqualTo("royalarena-internal-secret-dev");
    }

    // ===== Fallas: culpa del usuario vs culpa del sistema =====

    /**
     * Distinguirlas importa: NO_ACTIVE_DECK lo arregla el jugador configurando
     * su mazo; DECK_SERVICE_UNAVAILABLE lo arregla reintentando. Mezclarlas
     * daría un mensaje inútil.
     */
    @Test
    @DisplayName("404 significa 'no tienes mazo activo', no 'servicio caido'")
    void notFoundMeansNoActiveDeck() {
        DeckAndCardsClient client = clientReturning(HttpStatus.NOT_FOUND,
                "{\"status\":404,\"message\":\"No active deck found for user 99\"}");

        assertThatThrownBy(() -> client.fetchActiveDeck(99L))
                .isInstanceOf(NoActiveDeckException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("Un 500 del otro servicio es indisponibilidad, no culpa del jugador")
    void serverErrorMeansUnavailable() {
        DeckAndCardsClient client = clientReturning(
                HttpStatus.INTERNAL_SERVER_ERROR, "{\"status\":500}");

        assertThatThrownBy(() -> client.fetchActiveDeck(1L))
                .isInstanceOf(DeckServiceUnavailableException.class);
    }

    @Test
    @DisplayName("Un mazo vacio se trata como si no hubiera mazo")
    void emptyDeckIsTreatedAsNoDeck() {
        DeckAndCardsClient client = clientReturning(HttpStatus.OK,
                "{\"id\":1,\"name\":\"Vacio\",\"userId\":1,\"isActive\":true,\"cards\":[]}");

        assertThatThrownBy(() -> client.fetchActiveDeck(1L))
                .isInstanceOf(NoActiveDeckException.class);
    }

    @Test
    @DisplayName("Un mazo sin lista de cartas tampoco pasa")
    void nullCardsIsTreatedAsNoDeck() {
        DeckAndCardsClient client = clientReturning(HttpStatus.OK,
                "{\"id\":1,\"name\":\"X\",\"userId\":1,\"isActive\":true}");

        assertThatThrownBy(() -> client.fetchActiveDeck(1L))
                .isInstanceOf(NoActiveDeckException.class);
    }

    /**
     * Sin timeout, un Deck-and-Cards colgado bloquearía indefinidamente el
     * thread del jugador que pide partida. El timeout de 3s es lo que hace que
     * la falla sea rápida y visible.
     */
    @Test
    @DisplayName("Un servicio colgado corta por timeout, no espera para siempre")
    void hangingServiceTimesOut() {
        ExchangeFunction neverResponds = request ->
                Mono.delay(Duration.ofSeconds(30)).then(Mono.empty());
        DeckAndCardsClient client = new DeckAndCardsClient(
                WebClient.builder().exchangeFunction(neverResponds),
                "http://localhost:8081", "secret");

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.fetchActiveDeck(1L))
                .isInstanceOf(DeckServiceUnavailableException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("debe cortar cerca del timeout de 3s, no colgarse")
                .isLessThan(6000);
    }
}