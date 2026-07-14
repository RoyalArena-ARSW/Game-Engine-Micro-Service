package edu.eci.arsw.RoyalArena.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuración de WebSocket + STOMP para la comunicación en tiempo real
 * de las partidas.
 *
 * - Los clientes se conectan al endpoint /ws-game (con SockJS de fallback).
 * - Se suscriben a /topic/match/{matchId} para RECIBIR el estado.
 * - Envían acciones a /app/match/{matchId}/... que llegan a los @MessageMapping.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-game")
                .setAllowedOriginPatterns("*")  // Permisivo para desarrollo
                .withSockJS();                   // Fallback para navegadores sin WebSocket nativo
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker simple en memoria: los mensajes a /topic/** se enrutan a los suscritos.
        registry.enableSimpleBroker("/topic");
        // Los mensajes de clientes van a destinos que empiezan con /app.
        registry.setApplicationDestinationPrefixes("/app");
    }
}