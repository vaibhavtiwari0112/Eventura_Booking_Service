package com.eventura.booking.mapper;

import com.eventura.booking.domain.Booking;
import com.eventura.booking.domain.BookingItem;
import com.eventura.booking.dto.BookingResponse;

import java.util.List;
import java.util.stream.Collectors;

public class BookingMapper {

    public static BookingResponse toResponse(Booking booking) {
        // Flatten seatIds from all booking items
        List<String> allSeats = booking.getItems().stream()
                .flatMap(item -> item.getSeatIds().stream())
                .collect(Collectors.toList());

        return new BookingResponse(
                booking.getId(),
                booking.getUserId(),
                booking.getShowId(),
                booking.getStatus(),
                booking.getTotalAmount(),
                allSeats
        );
    }
}
