package com.eventura.booking.scheduling;

import com.eventura.booking.service.BookingManageService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class BookingCleanupScheduler {
    private final BookingManageService bookingManageService;
    private final int pendingTimeoutSeconds;

    public BookingCleanupScheduler(BookingManageService bookingManageService,
                                   org.springframework.core.env.Environment env) {
        this.bookingManageService = bookingManageService;
        this.pendingTimeoutSeconds = Integer.parseInt(env.getProperty("eventura.booking.pending-timeout-seconds", "150"));
    }

    @Scheduled(fixedDelayString = "86400000")
    public void cleanup() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(pendingTimeoutSeconds);
        bookingManageService.cancelStalePendingBookings(cutoff);
    }
}
