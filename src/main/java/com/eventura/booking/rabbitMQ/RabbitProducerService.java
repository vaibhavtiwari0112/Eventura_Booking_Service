package com.eventura.booking.rabbitMQ;

import com.eventura.booking.events.BookingCreatedEvent;
import com.eventura.booking.dto.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RabbitProducerService {
    private static final Logger log = LoggerFactory.getLogger(RabbitProducerService.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange:booking.exchange}")
    private String exchange;

    @Value("${rabbitmq.booking.routing-key:booking.created}")
    private String bookingRoutingKey;

    @Value("${rabbitmq.notification.routing-key:notification.event}")
    private String notificationRoutingKey;

    public RabbitProducerService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishBookingCreated(BookingCreatedEvent event) {
        log.info("📤 Publishing BookingCreatedEvent to RabbitMQ: {}", event);
        rabbitTemplate.convertAndSend(exchange, bookingRoutingKey, event);
    }

    public void publishNotification(NotificationEvent event) {
        log.info("📤 Publishing NotificationEvent to RabbitMQ: {}", event);
        rabbitTemplate.convertAndSend(exchange, notificationRoutingKey, event);
    }
}

