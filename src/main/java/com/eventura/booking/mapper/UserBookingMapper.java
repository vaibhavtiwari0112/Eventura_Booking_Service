package com.eventura.booking.mapper;

import com.eventura.booking.domain.Booking;
import com.eventura.booking.domain.BookingItem;
import com.eventura.booking.dto.BookingItemResponse;
import com.eventura.booking.dto.UserBookingResponse;

import java.util.stream.Collectors;

public class UserBookingMapper {

    public static UserBookingResponse toResponse(Booking b) {
        if (b == null) return null;

        return UserBookingResponse.builder()
                .id(b.getId())
                .userId(b.getUserId())
                .showId(b.getShowId())
                .hallId(b.getHallId())
                .status(b.getStatus())
                .totalAmount(b.getTotalAmount())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                // map items properly
                .items(b.getItems().stream()
                        .map(UserBookingMapper::mapItem)
                        .collect(Collectors.toList()))
                // flatten seatIds across all booking items
                .seatIds(b.getItems().stream()
                        .flatMap(it -> it.getSeatIds().stream())
                        .collect(Collectors.toList()))
                .build();
    }

    private static BookingItemResponse mapItem(BookingItem it) {
        return BookingItemResponse.builder()
                .id(it.getId())
                .seatIds(it.getSeatIds()) // ✅ list of seats
                .price(it.getPrice())
                .ticketType(null) // or map if you add it later
                .build();
    }
}

