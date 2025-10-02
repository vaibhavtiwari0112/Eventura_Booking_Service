package com.eventura.booking.dto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class NotificationEvent {
    private UUID eventId;
    private String eventType; // BOOKING_CONFIRMED / BOOKING_CANCELLED
    private OffsetDateTime timestamp;
    private UUID bookingId;
    private UUID userId;
    private String userEmail;
    private UUID showId;
    private UUID hallId;
    private String movieTitle;
    private String hallName;
    private OffsetDateTime showTime;
    private List<String> seats;
    private double amount;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    private String username;

    // Getters and setters
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public OffsetDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(OffsetDateTime timestamp) { this.timestamp = timestamp; }

    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public UUID getShowId() { return showId; }
    public void setShowId(UUID showId) { this.showId = showId; }

    public UUID getHallId() { return hallId; }
    public void setHallId(UUID hallId) { this.hallId = hallId; }

    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String getHallName() { return hallName; }
    public void setHallName(String hallName) { this.hallName = hallName; }

    public OffsetDateTime getShowTime() { return showTime; }
    public void setShowTime(OffsetDateTime showTime) { this.showTime = showTime; }

    public List<String> getSeats() { return seats; }
    public void setSeats(List<String> seats) { this.seats = seats; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
