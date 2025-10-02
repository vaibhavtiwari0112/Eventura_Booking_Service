package com.eventura.booking.dto;

import com.eventura.booking.domain.BookingStatus;
import lombok.*;

        import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserBookingResponse  {
    private UUID id;
    private UUID userId;
    private UUID showId;
    private UUID hallId;
    private BookingStatus status;
    private double totalAmount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<BookingItemResponse> items;
    private List<String> seatIds;      // FRONTEND expects b.seatIds
    // Enriched fields (optional if catalog/show services available)
    private String movieTitle;
    private String hallName;
    private String location;
    private OffsetDateTime time;       // show start time
}