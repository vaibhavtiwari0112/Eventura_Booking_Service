package com.eventura.booking.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "booking_items")
public class BookingItem {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    // store seat ids in a separate collection table (booking_item_seat_ids)
    @ElementCollection
    @CollectionTable(
            name = "booking_item_seat_ids",
            joinColumns = @JoinColumn(name = "booking_item_id")
    )
    @Column(name = "seat_id", nullable = false, length = 255)
    private List<String> seatIds = new ArrayList<>();

    @Column(name = "price", nullable = false)
    private double price;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // Ensures defaults are set before persisting
    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    // --- Constructors ---
    public BookingItem() {}

    // Accept a list of seats
    public BookingItem(List<String> seatIds, double price) {
        this.seatIds = seatIds == null ? new ArrayList<>() : new ArrayList<>(seatIds);
        this.price = price;
    }

    // Convenience constructor for a single seat (used by current service code)
    public BookingItem(String seatId, double price) {
        this.seatIds = new ArrayList<>();
        if (seatId != null) this.seatIds.add(seatId);
        this.price = price;
    }

    // --- Getters & Setters ---
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Booking getBooking() {
        return booking;
    }

    public void setBooking(Booking booking) {
        this.booking = booking;
    }

    public List<String> getSeatIds() {
        return seatIds;
    }

    public void setSeatIds(List<String> seatIds) {
        this.seatIds = seatIds == null ? new ArrayList<>() : new ArrayList<>(seatIds);
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
