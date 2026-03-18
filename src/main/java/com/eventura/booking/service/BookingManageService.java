package com.eventura.booking.service;

import com.eventura.booking.domain.Booking;
import com.eventura.booking.domain.BookingItem;
import com.eventura.booking.domain.BookingStatus;
import com.eventura.booking.dto.*;
import com.eventura.booking.lock.SeatLockService;
import com.eventura.booking.mapper.UserBookingMapper;
import com.eventura.booking.rabbitMQ.RabbitProducerService;
import com.eventura.booking.repository.BookingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BookingManageService {
    private static final Logger log = LoggerFactory.getLogger(BookingManageService.class);

    private final SeatLockService seatLockService;
    private final BookingRepository bookingRepository;
    private final MeterRegistry meterRegistry;
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitProducerService rabbitProducer;
    private final String showServiceUrl;

    public BookingManageService(SeatLockService seatLockService,
                                BookingRepository bookingRepository,
                                MeterRegistry meterRegistry,
                                RestTemplate restTemplate,
                                RabbitProducerService rabbitProducer,
                                RedisTemplate<String, Object> redisTemplate,
                                @Value("${services.show.base-url:https://show-service.onrender.com}") String showServiceUrl) {
        this.seatLockService = seatLockService;
        this.bookingRepository = bookingRepository;
        this.meterRegistry = meterRegistry;
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.showServiceUrl = showServiceUrl;
        this.rabbitProducer = rabbitProducer;
    }

    @Value("${services.show.base-url:https://show-service.onrender.com}")
    private String showServiceBaseUrl;

    @Value("${services.catalog.base-url:https://catalog-service-wvzz.onrender.com}")
    private String catalogServiceBaseUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // Get Seats
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<String> getSeatsLocked(UUID showId, UUID userId) {
        return seatLockService.getSeatsLockedByUser(showId.toString(), userId.toString());
    }

    @Transactional(readOnly = true)
    public List<String> getBookedSeatsForShow(UUID showId) {
        return bookingRepository.findByShowIdAndStatus(showId, BookingStatus.CONFIRMED).stream()
                .flatMap(b -> b.getItems().stream().flatMap(item -> item.getSeatIds().stream()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<String> getAllLockedSeatsForShow(UUID showId) {
        return seatLockService.getAllLockedSeatsForShow(showId.toString());
    }

    public BookingRepository getBookingRepository() {
        return this.bookingRepository;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create Booking
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public Booking createBooking(UUID userId, UUID showId, List<String> seatIds,
                                 double totalAmount, UUID hallId) {
        log.info("Creating booking | userId={} showId={} hallId={} seats={}",
                userId, showId, hallId, seatIds);

        // Step 1: Redis lock (atomic SETNX per seat)
        boolean redisLocked = seatLockService.tryLockSeats(
                showId.toString(), seatIds, userId.toString());

        if (!redisLocked) {
            log.warn("Seat lock failed in Redis | showId={} seats={}", showId, seatIds);
            throw new IllegalStateException("Seats are already locked by another user: " + seatIds);
        }

        // Step 2: ShowService lock — rollback Redis if it fails
        try {
            updateSeatsInShowService("/shows/lock-seats", showId, hallId, seatIds);
        } catch (Exception e) {
            seatLockService.releaseLocks(showId.toString(), seatIds, userId.toString());
            log.error("ShowService lock failed, Redis lock released | seats={}", seatIds);
            throw new IllegalStateException("Failed to lock seats in ShowService", e);
        }

        // Step 3: Save booking PENDING — this commits immediately
        Booking booking = new Booking(userId, showId, BookingStatus.PENDING);
        booking.setHallId(hallId);
        booking.addItem(new BookingItem(seatIds, totalAmount));
        bookingRepository.save(booking);

        // Step 4: Update Redis seat state
        updateRedis(showId, seatIds, "lock");

        meterRegistry.counter("eventura.bookings.created").increment();
        log.info("Booking created | bookingId={} totalAmount={}",
                booking.getId(), booking.getTotalAmount());

        return booking;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confirm Booking
    //
    // KEY FIX: Split into two methods:
    //   1. commitConfirm()  — REQUIRES_NEW transaction, commits DB immediately
    //   2. confirmBooking() — calls commitConfirm(), THEN calls external services
    //                         OUTSIDE the transaction so failures don't roll back DB
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Commits the CONFIRMED status to DB in its own transaction.
     * REQUIRES_NEW ensures this commits before external calls happen.
     * Returns the confirmed booking + seat list for downstream use.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Booking commitConfirm(UUID bookingId) {
        Booking b = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if (b.getStatus() != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm booking in status: " + b.getStatus());
        }

        b.setStatus(BookingStatus.CONFIRMED);
        Booking saved = bookingRepository.save(b); // @Version fires — commits on method exit
        log.info("Booking status committed to CONFIRMED | bookingId={}", bookingId);
        return saved;
    }

    /**
     * Full confirm flow — DB commit happens first, external calls after.
     * ShowService or RabbitMQ failures will NOT roll back the DB status.
     */
    public Booking confirmBooking(UUID bookingId, UUID hallId) {
        // ✅ Step 1: Commit DB status change in its own transaction
        //    This returns only after the DB row says CONFIRMED
        Booking confirmed;
        try {
            confirmed = commitConfirm(bookingId);
        } catch (OptimisticLockingFailureException e) {
            // Another thread already confirmed/cancelled — re-read current state
            log.warn("Optimistic lock conflict on confirm | bookingId={}", bookingId);
            throw new IllegalStateException(
                    "Booking was modified concurrently. Please check booking status.", e);
        }

        List<String> seats = confirmed.getItems().stream()
                .flatMap(item -> item.getSeatIds().stream()).toList();

        // ✅ Step 2: Notify ShowService — OUTSIDE transaction
        //    Failure here does NOT roll back the CONFIRMED status
        try {
            updateSeatsInShowService("/shows/confirm-seats",
                    confirmed.getShowId(), hallId, seats);
        } catch (Exception e) {
            // DB is already CONFIRMED — just log, don't throw
            // ShowService can be synced later via a reconciliation job
            log.error("ShowService confirm-seats failed after DB commit " +
                            "| bookingId={} — DB status is CONFIRMED, manual sync may be needed: {}",
                    bookingId, e.getMessage());
        }

        // ✅ Step 3: Update Redis seat state — OUTSIDE transaction
        try {
            updateRedis(confirmed.getShowId(), seats, "confirm");
        } catch (Exception e) {
            log.warn("Redis update failed after confirm — TTL will clean up: {}", e.getMessage());
        }

        meterRegistry.counter("eventura.bookings.confirmed").increment();

        // ✅ Step 4: RabbitMQ notification — OUTSIDE transaction
        try {
            rabbitProducer.publishNotification(
                    buildNotificationEvent(confirmed, "BOOKING_CONFIRMED"));
        } catch (Exception e) {
            log.error("RabbitMQ notification failed (booking is confirmed in DB): {}",
                    e.getMessage());
        }

        return confirmed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel Booking — same pattern: DB commit first, external calls after
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Booking commitCancel(UUID bookingId) {
        Booking b = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if (b.getStatus() == BookingStatus.CANCELLED) {
            log.warn("Booking {} already cancelled, skipping", bookingId);
            return b; // idempotent
        }

        b.setStatus(BookingStatus.CANCELLED);
        return bookingRepository.save(b);
    }

    public Booking cancelBooking(UUID bookingId, UUID hallId, String reason) {
        // ✅ DB commit first
        Booking cancelled;
        try {
            cancelled = commitCancel(bookingId);
        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict on cancel | bookingId={}", bookingId);
            throw new IllegalStateException(
                    "Booking was modified concurrently. Please check booking status.", e);
        }

        List<String> seats = cancelled.getItems().stream()
                .flatMap(item -> item.getSeatIds().stream()).toList();

        // ✅ External calls after commit — failures don't affect DB status
        try {
            updateSeatsInShowService("/shows/release-seats",
                    cancelled.getShowId(), hallId, seats);
        } catch (Exception e) {
            log.error("ShowService release-seats failed after cancel DB commit | bookingId={}: {}",
                    bookingId, e.getMessage());
        }

        try {
            updateRedis(cancelled.getShowId(), seats, "release");
        } catch (Exception e) {
            log.warn("Redis release failed after cancel: {}", e.getMessage());
        }

        meterRegistry.counter("eventura.bookings.cancelled").increment();

        try {
            NotificationEvent event = buildNotificationEvent(cancelled, "BOOKING_CANCELLED");
            event.setCancelReason(reason);
            rabbitProducer.publishNotification(event);
        } catch (Exception e) {
            log.error("RabbitMQ cancel notification failed: {}", e.getMessage());
        }

        return cancelled;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Payment Success
    // ─────────────────────────────────────────────────────────────────────────

    public Booking markPaymentSuccess(UUID bookingId, UUID hallId) {
        log.info("Payment success | bookingId={} hallId={}", bookingId, hallId);
        return confirmBooking(bookingId, hallId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lock Seats (standalone — used by /bookings/lock endpoint)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public boolean lockSeats(UUID showId, List<String> seatIds, UUID userId) {
        boolean success = seatLockService.tryLockSeats(
                showId.toString(), seatIds, userId.toString());
        if (success) {
            updateRedis(showId, seatIds, "lock");
            log.info("Locally locked seats | showId={} seats={}", showId, seatIds);
        }
        return success;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stale Booking Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public int cancelStalePendingBookings(OffsetDateTime cutoffTime) {
        List<Booking> staleBookings = bookingRepository
                .findByStatusAndCreatedAtBefore(BookingStatus.PENDING, cutoffTime);

        log.info("Found {} stale pending bookings before {}", staleBookings.size(), cutoffTime);

        for (Booking b : staleBookings) {
            try {
                b.setStatus(BookingStatus.CANCELLED);
                bookingRepository.save(b);

                List<String> seats = b.getItems().stream()
                        .flatMap(item -> item.getSeatIds().stream()).toList();

                seatLockService.releaseLocks(
                        b.getShowId().toString(), seats, b.getUserId().toString());

                try {
                    updateSeatsInShowService("/shows/release-seats", b.getShowId(), null, seats);
                } catch (Exception e) {
                    log.warn("ShowService release failed for stale booking {}: {}", b.getId(), e.getMessage());
                }

                try {
                    updateRedis(b.getShowId(), seats, "release");
                } catch (Exception e) {
                    log.warn("Redis release failed for stale booking {}: {}", b.getId(), e.getMessage());
                }

                log.info("Auto-cancelled stale booking | bookingId={} userId={}", b.getId(), b.getUserId());

            } catch (OptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict on stale cancel — skipping | bookingId={}", b.getId());
            }
        }

        meterRegistry.counter("eventura.bookings.auto_cancelled").increment(staleBookings.size());
        return staleBookings.size();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unlock and Cancel
    // ─────────────────────────────────────────────────────────────────────────

    public Booking unlockAndCancelBooking(UUID bookingId, UUID hallId, String reason) {
        log.info("Unlocking and cancelling | bookingId={} hallId={} reason={}",
                bookingId, hallId, reason);

        Booking b = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if (b.getStatus() == BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot unlock a confirmed booking");
        }

        // Commit cancel first
        Booking cancelled = commitCancel(bookingId);

        List<String> seats = cancelled.getItems().stream()
                .flatMap(item -> item.getSeatIds().stream()).toList();

        try {
            seatLockService.releaseLocks(
                    cancelled.getShowId().toString(), seats, cancelled.getUserId().toString());
        } catch (Exception e) {
            log.warn("Redis lock release failed: {}", e.getMessage());
        }

        try {
            updateSeatsInShowService("/shows/release-seats",
                    cancelled.getShowId(), hallId, seats);
        } catch (Exception e) {
            log.error("ShowService release failed after unlock-cancel | bookingId={}: {}",
                    bookingId, e.getMessage());
        }

        try {
            updateRedis(cancelled.getShowId(), seats, "release");
        } catch (Exception e) {
            log.warn("Redis update failed after unlock-cancel: {}", e.getMessage());
        }

        meterRegistry.counter("eventura.bookings.unlocked_cancelled").increment();

        try {
            NotificationEvent event = buildNotificationEvent(cancelled, "BOOKING_UNLOCKED_CANCELLED");
            event.setCancelReason(reason);
            rabbitProducer.publishNotification(event);
        } catch (Exception e) {
            log.error("RabbitMQ unlock-cancel notification failed: {}", e.getMessage());
        }

        return cancelled;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification Event Builder
    // ─────────────────────────────────────────────────────────────────────────

    private NotificationEvent buildNotificationEvent(Booking booking, String eventType) {
        ShowDto show = fetchShowDetails(booking.getShowId());
        OffsetDateTime showTime  = show != null ? show.getStartTime() : null;
        String movieTitle        = show != null ? fetchMovieTitle(show.getMovieId()) : null;
        String hallName          = fetchHallName(booking.getHallId());

        NotificationEvent event = new NotificationEvent();
        event.setEventType(eventType);
        event.setBookingId(booking.getId());
        event.setUserId(booking.getUserId());
        event.setShowId(booking.getShowId());
        event.setSeats(booking.getItems().stream()
                .flatMap(item -> item.getSeatIds().stream()).toList());
        event.setTotalAmount(booking.getTotalAmount());
        event.setShowTime(showTime);
        event.setMovieTitle(movieTitle);
        event.setHallName(hallName);
        event.setUserEmail(fetchUserEmail(booking.getUserId()));
        event.setUsername(fetchUsername(booking.getUserId()));
        return event;
    }

    private String fetchUserEmail(UUID userId) { return "vaibhav45tiwari@gmail.com"; }
    private String fetchUsername(UUID userId)   { return "Vaibhav"; }

    private String fetchHallName(UUID hallId) {
        if (hallId == null) return null;
        try {
            HallDto hall = restTemplate.getForObject(
                    catalogServiceBaseUrl + "/catalog/halls/" + hallId, HallDto.class);
            return hall != null ? hall.getName() : null;
        } catch (Exception e) {
            log.warn("Failed to fetch hall name for hallId={}: {}", hallId, e.getMessage());
            return null;
        }
    }

    private String fetchMovieTitle(UUID movieId) {
        if (movieId == null) return null;
        try {
            MovieDto movie = restTemplate.getForObject(
                    catalogServiceBaseUrl + "/catalog/movies/" + movieId, MovieDto.class);
            return movie != null ? movie.getTitle() : null;
        } catch (Exception e) {
            log.warn("Failed to fetch movie title for movieId={}: {}", movieId, e.getMessage());
            return null;
        }
    }

    private ShowDto fetchShowDetails(UUID showId) {
        if (showId == null) return null;
        try {
            return restTemplate.getForObject(
                    showServiceBaseUrl + "/shows/" + showId, ShowDto.class);
        } catch (Exception e) {
            log.warn("Failed to fetch show details for showId={}: {}", showId, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ShowService HTTP call
    // ─────────────────────────────────────────────────────────────────────────

    private void updateSeatsInShowService(String endpoint, UUID showId,
                                          UUID hallId, List<String> seats) {
        Map<String, Object> req = new HashMap<>();
        req.put("showId", showId);
        req.put("seats",  seats);
        req.put("hallId", hallId);

        try {
            log.info("Sending to ShowService {} | payload={}", endpoint, req);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(
                    showServiceUrl + endpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(req, headers),
                    Void.class
            );
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Seat conflict in ShowService [{}]: {}", endpoint, e.getMessage());
            throw new IllegalStateException("Seats already locked/booked in ShowService", e);
        } catch (Exception e) {
            log.error("Failed to update ShowService [{}]: {}", endpoint, e.getMessage(), e);
            throw new IllegalStateException("Failed to update ShowService: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Redis seat state update
    // ─────────────────────────────────────────────────────────────────────────

    private void updateRedis(UUID showId, List<String> seats, String action) {
        String base      = "booking:show:" + showId + ":seats";
        String available = base + ":available";
        String locked    = base + ":locked";
        String booked    = base + ":booked";

        switch (action) {
            case "lock" -> {
                redisTemplate.opsForSet().remove(available, seats.toArray());
                redisTemplate.opsForSet().add(locked, seats.toArray());
            }
            case "confirm" -> {
                redisTemplate.opsForSet().remove(locked, seats.toArray());
                redisTemplate.opsForSet().add(booked, seats.toArray());
            }
            case "release" -> {
                redisTemplate.opsForSet().remove(locked, seats.toArray());
                redisTemplate.opsForSet().remove(booked, seats.toArray());
                redisTemplate.opsForSet().add(available, seats.toArray());
            }
        }
        log.info("Redis {} | show={} seats={}", action, showId, seats);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Misc
    // ─────────────────────────────────────────────────────────────────────────

    public void notifySeatStatusChange(UUID showId, UUID hallId,
                                       List<String> seatIds, String status) {
        try {
            String path = status.equals("AVAILABLE") ? "release-seats" : "confirm-seats";
            SeatStatusUpdateRequest payload =
                    new SeatStatusUpdateRequest(showId, hallId, seatIds, status);
            restTemplate.postForEntity(
                    showServiceUrl + "/shows/" + path, payload, Void.class);
            log.info("Seat status updated | show={} hall={} seats={} status={}",
                    showId, hallId, seatIds, status);
        } catch (Exception e) {
            log.error("Failed to notify ShowService: {}", e.getMessage(), e);
        }
    }

    public List<UserBookingResponse> getBookingsForUser(UUID userId) {
        List<BookingStatus> statuses = List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);
        return bookingRepository.findByUserIdAndStatusIn(userId, statuses)
                .stream().map(this::enrichAndMap).collect(Collectors.toList());
    }

    private UserBookingResponse enrichAndMap(Booking booking) {
        UserBookingResponse resp = UserBookingMapper.toResponse(booking);
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildAuthHeaders());

            ResponseEntity<ShowDto> showResp = restTemplate.exchange(
                    showServiceBaseUrl + "/shows/" + booking.getShowId(),
                    HttpMethod.GET, entity, ShowDto.class);
            ShowDto show = showResp.getBody();

            if (show != null) {
                resp.setTime(show.getStartTime());
                if (show.getMovieId() != null) {
                    ResponseEntity<MovieDto> movieResp = restTemplate.exchange(
                            catalogServiceBaseUrl + "/catalog/movies/" + show.getMovieId(),
                            HttpMethod.GET, entity, MovieDto.class);
                    if (movieResp.getBody() != null)
                        resp.setMovieTitle(movieResp.getBody().getTitle());
                }
            }

            if (booking.getHallId() != null) {
                ResponseEntity<HallDto> hallResp = restTemplate.exchange(
                        catalogServiceBaseUrl + "/catalog/halls/" + booking.getHallId(),
                        HttpMethod.GET, entity, HallDto.class);
                HallDto hall = hallResp.getBody();
                if (hall != null) {
                    resp.setHallName(hall.getName());
                    if (hall.getTheaterId() != null) {
                        ResponseEntity<TheaterDto> theaterResp = restTemplate.exchange(
                                catalogServiceBaseUrl + "/catalog/theatres/" + hall.getTheaterId(),
                                HttpMethod.GET, entity, TheaterDto.class);
                        TheaterDto theater = theaterResp.getBody();
                        if (theater != null) {
                            resp.setLocation(theater.getName() +
                                    (theater.getCity() != null ? ", " + theater.getCity() : ""));
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not enrich booking {}: {}", booking.getId(), ex.getMessage());
        }
        return resp;
    }

    private HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String auth = attrs.getRequest().getHeader("Authorization");
            if (auth != null) headers.set("Authorization", auth);
        }
        return headers;
    }

    public BookingStatus getBookingStatus(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .map(Booking::getStatus)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Booking not found with id " + bookingId));
    }
}