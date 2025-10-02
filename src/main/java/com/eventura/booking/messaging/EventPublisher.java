package com.eventura.booking.messaging;

import com.eventura.booking.events.BookingCreatedEvent;
import com.eventura.booking.dto.NotificationEvent;

public interface EventPublisher {
    void publishBookingCreated(BookingCreatedEvent event);
    void publishNotification(NotificationEvent event);
}
