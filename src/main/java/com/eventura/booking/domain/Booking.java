package com.eventura.booking.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    // ✅ Explicit column name — avoids PostgreSQL reserved word collision
    //    and makes the schema migration unambiguous
    @Version
    @Column(name = "booking_version", nullable = false)
    private Long version;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "hall_id")
    private UUID hallId;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BookingStatus status;

    @Column(name = "total_amount", nullable = false)
    private double totalAmount = 0.0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ✅ FetchType.EAGER — items must be loaded when Booking is loaded.
    //    cancelBooking/confirmBooking read items inside the same transaction
    //    that modifies status; LAZY here would cause the same LazyInit problem.
    @OneToMany(
            mappedBy = "booking",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER
    )
    private List<BookingItem> items = new ArrayList<>();

    // --- Lifecycle hooks ---
    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Constructors ---
    public Booking() {}

    public Booking(UUID userId, UUID showId, BookingStatus status) {
        this.userId = userId;
        this.showId = showId;
        this.status = status;
    }

    // --- Utility methods ---

    /**
     * Always use this method to add items — it wires up both sides
     * of the bidirectional relationship and keeps totalAmount in sync.
     */
    public void addItem(BookingItem item) {
        if (item == null) return;
        item.setBooking(this);          // ← wires the FK on BookingItem side
        this.items.add(item);
        this.totalAmount += item.getPrice();
    }

    public void removeItem(BookingItem item) {
        if (item == null) return;
        if (this.items.remove(item)) {
            this.totalAmount -= item.getPrice();
            item.setBooking(null);
        }
    }

    // --- Getters & Setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getShowId() { return showId; }
    public void setShowId(UUID showId) { this.showId = showId; }

    public UUID getHallId() { return hallId; }
    public void setHallId(UUID hallId) { this.hallId = hallId; }

    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }

    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<BookingItem> getItems() { return items; }

    /**
     * Use setItems only for bulk replacement (e.g. deserialization).
     * Prefer addItem() for normal use to keep totalAmount accurate.
     */
    public void setItems(List<BookingItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
        this.totalAmount = this.items.stream().mapToDouble(BookingItem::getPrice).sum();
    }

    public Long getVersion() { return version; }
}