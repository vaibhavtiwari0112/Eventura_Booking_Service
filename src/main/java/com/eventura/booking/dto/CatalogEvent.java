package com.eventura.booking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CatalogEvent {

    @JsonProperty("event_type")
    private String type;

    private Payload payload;

    @Data
    public static class Payload {
        private UUID id;
        private String title;
        private String name;
        private UUID theaterId;
        private List<String> seats;   // ✅ this is where seat list comes from
    }

    // Convenience accessors for cache
    public UUID getId() { return payload != null ? payload.id : null; }
    public String getTitle() { return payload != null ? payload.title : null; }
    public String getName() { return payload != null ? payload.name : null; }
    public UUID getTheaterId() { return payload != null ? payload.theaterId : null; }
    public List<String> getSeats() { return payload != null ? payload.seats : List.of(); }
}
