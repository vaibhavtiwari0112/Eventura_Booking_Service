package com.eventura.booking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public class SeatStatusUpdateRequest {
    private UUID showId;
    private UUID hallId;

    @JsonProperty("seats")          // ← tells Jackson to serialize as "seats" not "seatIds"
    private List<String> seatIds;

    private String status;

    public SeatStatusUpdateRequest(UUID showId, UUID hallId,
                                   List<String> seatIds, String status) {
        this.showId  = showId;
        this.hallId  = hallId;
        this.seatIds = seatIds;
        this.status  = status;
    }

    public UUID getShowId()             { return showId; }
    public void setShowId(UUID showId)  { this.showId = showId; }

    public UUID getHallId()             { return hallId; }
    public void setHallId(UUID hallId)  { this.hallId = hallId; }

    @JsonProperty("seats")
    public List<String> getSeatIds()              { return seatIds; }
    public void setSeatIds(List<String> seatIds)  { this.seatIds = seatIds; }

    public String getStatus()               { return status; }
    public void setStatus(String status)    { this.status = status; }
}