package com.eventura.booking.dto;

import lombok.*;
import java.util.List;
import java.util.UUID;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BookingItemResponse {
    private UUID id;
    private List<String> seatIds; // ✅ list instead of single seatId
    private double price;
    private String ticketType; // optional, only if you plan to use it
}