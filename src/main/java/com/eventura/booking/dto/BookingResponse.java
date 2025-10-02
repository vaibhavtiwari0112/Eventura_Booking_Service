package com.eventura.booking.dto;

import com.eventura.booking.domain.BookingStatus;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID userId,
        UUID showId,
        BookingStatus status,
        double totalAmount,
        List<String> seatIds
) {}
