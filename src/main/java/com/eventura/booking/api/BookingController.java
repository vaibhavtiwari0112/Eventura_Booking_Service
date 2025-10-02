package com.eventura.booking.api;

import com.eventura.booking.domain.Booking;
import com.eventura.booking.domain.BookingStatus;
import com.eventura.booking.dto.ApiResponse;
import com.eventura.booking.dto.CreateBookingRequest;
import com.eventura.booking.dto.UserBookingResponse;
import com.eventura.booking.mapper.BookingMapper;
import com.eventura.booking.service.BookingManageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingManageService bookingManageService;

    public BookingController(BookingManageService bookingManageService) {
        this.bookingManageService = bookingManageService;
    }

    // ------------------- Create Booking -------------------
    @PostMapping
    public ResponseEntity<ApiResponse<?>> createBooking(@RequestBody CreateBookingRequest req) {
        try {
            Booking booking = bookingManageService.createBooking(
                    req.getUserId(),
                    req.getShowId(),
                    req.getSeatIds(),
                    req.getAmount(),
                    req.getHallId()
            );

            // 🔗 Tell Show Service: mark seats as LOCKED
            bookingManageService.notifySeatStatusChange(req.getShowId(), req.getHallId(), req.getSeatIds(), "LOCKED");

            return ResponseEntity.ok(ApiResponse.success("Booking created successfully", booking.getId()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(ApiResponse.failure(ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(ex.getMessage()));
        }
    }

    // ------------------- Confirm Booking (called by Payment Service) -------------------
    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirmBooking(
            @PathVariable UUID id,
            @RequestParam UUID hallId) {
        try {
            Booking booking = bookingManageService.confirmBooking(id, hallId);

            // 🔗 Update Show Service: mark seats as BOOKED
            bookingManageService.notifySeatStatusChange(
                    booking.getShowId(),
                    booking.getHallId(),
                    booking.getItems().stream()
                            .flatMap(item -> item.getSeatIds().stream())
                            .toList(),
                    "BOOKED"
            );

            return ResponseEntity.ok(BookingMapper.toResponse(booking));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(ApiResponse.failure("Error confirming booking: " + ex.getMessage()));
        }
    }

    // ------------------- Cancel Booking -------------------
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelBooking(
            @PathVariable UUID id,
            @RequestParam UUID hallId,
            @RequestParam(required = false) String reason) {
        try {
            Booking booking = bookingManageService.cancelBooking(
                    id,
                    hallId,
                    reason != null ? reason : "user_cancelled"
            );

            // 🔗 Update Show Service: mark seats back to AVAILABLE
            bookingManageService.notifySeatStatusChange(
                    booking.getShowId(),
                    booking.getHallId(),
                    booking.getItems().stream()
                            .flatMap(item -> item.getSeatIds().stream())
                            .toList(),
                    "AVAILABLE"
            );

            return ResponseEntity.ok(BookingMapper.toResponse(booking));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (Exception ex) {
            return ResponseEntity.status(500)
                    .body(ApiResponse.failure("Error cancelling booking: " + ex.getMessage()));
        }
    }

    // ------------------- Lock Seats -------------------
    @PostMapping("/lock")
    public ResponseEntity<ApiResponse<?>> lockSeats(@RequestBody CreateBookingRequest req) {
        boolean locked = bookingManageService.lockSeats(req.getShowId(), req.getSeatIds(), req.getUserId());
        if (locked) {
            bookingManageService.notifySeatStatusChange(req.getShowId(), req.getHallId(), req.getSeatIds(), "LOCKED");
            return ResponseEntity.ok(ApiResponse.success("Seats locked successfully", req.getSeatIds()));
        } else {
            return ResponseEntity.status(409).body(ApiResponse.failure("Unable to lock seats"));
        }
    }

    // ------------------- Get Locked Seats for User -------------------
    @GetMapping("/locks")
    public ResponseEntity<ApiResponse<?>> getLockedSeats(
            @RequestParam UUID showId,
            @RequestParam UUID userId) {
        List<String> lockedSeats = bookingManageService.getSeatsLocked(showId, userId);
        return ResponseEntity.ok(ApiResponse.success("Locked seats retrieved", lockedSeats));
    }

    // ------------------- Get All Locked Seats for Show -------------------
    @GetMapping("/locks/show/{showId}")
    public ResponseEntity<ApiResponse<?>> getAllLockedSeats(@PathVariable UUID showId) {
        List<String> lockedSeats = bookingManageService.getAllLockedSeatsForShow(showId);
        return ResponseEntity.ok(ApiResponse.success("All locked seats for show retrieved", lockedSeats));
    }

    // ------------------- Get Booked Seats for Show -------------------
    @GetMapping("/booked/{showId}")
    public ResponseEntity<ApiResponse<?>> getBookedSeats(@PathVariable UUID showId) {
        List<String> bookedSeats = bookingManageService.getBookedSeatsForShow(showId);
        return ResponseEntity.ok(ApiResponse.success("Booked seats retrieved", bookedSeats));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getBookingStatus(@PathVariable UUID id) {
        try {
            BookingStatus status = bookingManageService.getBookingStatus(id);
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("status", status);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ------------------- Get Booking by ID -------------------
    @GetMapping("/{id}")
    public ResponseEntity<?> getBooking(@PathVariable UUID id) {
        return bookingManageService.getBookingRepository().findById(id)
                .map(booking -> ResponseEntity.ok(BookingMapper.toResponse(booking)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ------------------- Get All Bookings for User -------------------
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<UserBookingResponse>> getBookingsForUser(@PathVariable UUID userId) {
        List<UserBookingResponse> dtos = bookingManageService.getBookingsForUser(userId);
        return ResponseEntity.ok(dtos);
    }

    // ------------------- Payment Success (NEW) -------------------
    @PostMapping("/{id}/payment-success")
    public ResponseEntity<?> markPaymentSuccess(
            @PathVariable UUID id,
            @RequestParam UUID hallId
    ) {
        try {
            Booking booking = bookingManageService.markPaymentSuccess(id, hallId);

            // 🔗 Update Show Service: mark seats as BOOKED
            bookingManageService.notifySeatStatusChange(
                    booking.getShowId(),
                    booking.getHallId(),
                    booking.getItems().stream()
                            .flatMap(item -> item.getSeatIds().stream())
                            .toList(),
                    "BOOKED"
            );

            return ResponseEntity.ok(BookingMapper.toResponse(booking));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(ApiResponse.failure(
                    "Error marking payment success: " + ex.getMessage()
            ));
        }
    }
}
