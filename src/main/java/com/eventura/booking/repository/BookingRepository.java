package com.eventura.booking.repository;

import com.eventura.booking.domain.Booking;
import com.eventura.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findAllByShowIdAndStatus(UUID showId, BookingStatus status);
    List<Booking> findByShowIdAndStatus(UUID showId, BookingStatus status);

    List<Booking> findByUserId(UUID userId);
    List<Booking> findByUserIdAndStatusIn(UUID userId, List<BookingStatus> statuses);
    List<Booking> findByStatusAndCreatedAtBefore(BookingStatus status, OffsetDateTime before);
}
