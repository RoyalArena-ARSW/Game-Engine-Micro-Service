package edu.eci.arsw.RoyalArena.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de mensajería del lado PRODUCTOR.
 * TopicExchange: enruta por routing key con patrones. Publicamos con
 * "match.finished"; un consumidor puede bindear a esa key exacta, o a
 * "match.*" para recibir todos los eventos de partida.
 */
@Configuration
public class RabbitConfig {

    @Value("${royalarena.events.exchange}")
    private String exchangeName;

    @Bean
    public TopicExchange matchesExchange() {
        return new TopicExchange(exchangeName, true, false); // durable, no auto-delete
    }

    /**
     * Sin esto, Spring AMQP serializa con Java Serialization: ilegible en la
     * consola de RabbitMQ y solo consumible desde Java. Con JSON, el mensaje
     * es inspeccionable y cualquier lenguaje podría consumirlo.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}