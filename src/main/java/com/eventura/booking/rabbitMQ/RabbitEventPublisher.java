package com.eventura.booking.rabbitMQ;

import com.eventura.booking.dto.NotificationEvent;
import com.eventura.booking.events.BookingCreatedEvent;
import com.eventura.booking.messaging.EventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("rabbit")
public class RabbitEventPublisher implements EventPublisher {

    private final RabbitProducerService rabbitProducer;

    public RabbitEventPublisher(RabbitProducerService rabbitProducer) {
        this.rabbitProducer = rabbitProducer;
    }

    @Override
    public void publishBookingCreated(BookingCreatedEvent event) {
        rabbitProducer.publishBookingCreated(event);
    }

    @Override
    public void publishNotification(NotificationEvent event) {
        rabbitProducer.publishNotification(event);
    }
}
