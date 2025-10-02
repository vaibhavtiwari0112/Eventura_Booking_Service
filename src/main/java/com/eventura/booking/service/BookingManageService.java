package com.eventura.booking.service;

import com.eventura.booking.domain.Booking;
import com.eventura.booking.domain.BookingItem;
import com.eventura.booking.domain.BookingStatus;
import com.eventura.booking.dto.*;
import com.eventura.booking.lock.SeatLockService;
import com.eventura.booking.mapper.UserBookingMapper;
import com.eventura.booking.repository.BookingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
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

    private final String showServiceUrl;

    public BookingManageService(SeatLockService seatLockService,
                                BookingRepository bookingRepository,
                                MeterRegistry meterRegistry,
                                RestTemplate restTemplate,
                                RedisTemplate<String, Object> redisTemplate,
                                @Value("${show.service.url:http://localhost:8084}") String showServiceUrl) {
        this.seatLockService = seatLockService;
        this.bookingRepository = bookingRepository;
        this.meterRegistry = meterRegistry;
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.showServiceUrl = showServiceUrl;
    }

    @Value("${services.show.base-url:http://localhost:8084}")
    private String showServiceBaseUrl;

    @Value("${services.catalog.base-url:http://localhost:8083}")
    private String catalogServiceBaseUrl;

    private String redisKey(UUID showId) {
        return "booking:show:" + showId + ":seats";
    }

    // ---------------- Get Seats ----------------
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

    // ---------------- Create Booking ----------------
    @Transactional
    public Booking createBooking(UUID userId, UUID showId, List<String> seatIds, double totalAmount, UUID hallId) {
        log.info("🎟️ Creating booking | userId={} | showId={} | hallId={} | requestedSeats={}",
                userId, showId, hallId, seatIds);

        // Step 1: Lock in ShowService (source of truth)
        try {
            updateSeatsInShowService("/shows/lock-seats", showId, hallId, seatIds);
            //updateRedis(showId, seatIds, "lock");
        } catch (Exception e) {
            throw new IllegalStateException("❌ Failed to lock seats in ShowService", e);
        }

        // Step 2: Save booking in PENDING state
        Booking booking = new Booking(userId, showId, BookingStatus.PENDING);
        booking.setHallId(hallId);
        BookingItem bookingItem = new BookingItem(seatIds, totalAmount);
        booking.addItem(bookingItem);
        bookingRepository.save(booking);

        meterRegistry.counter("eventura.bookings.created").increment();
        log.info("✅ Booking created | bookingId={} | totalAmount={}", booking.getId(), booking.getTotalAmount());

        return booking;
    }

    // ---------------- Confirm Booking ----------------
    @Transactional
    public Booking confirmBooking(UUID bookingId, UUID hallId) {
        return bookingRepository.findById(bookingId).map(b -> {
            b.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(b);

            List<String> seats = b.getItems().stream()
                    .flatMap(item -> item.getSeatIds().stream())
                    .toList();

            // Confirm in ShowService + Redis
            updateSeatsInShowService("/shows/confirm-seats", b.getShowId(), hallId, seats);
            // updateRedis(b.getShowId(), seats, "confirm");

            meterRegistry.counter("eventura.bookings.confirmed").increment();
            log.info("✅ Booking confirmed | bookingId={} | seats={}", b.getId(), seats);

            return b;
        }).orElseThrow(() -> new IllegalArgumentException("Booking not found with id " + bookingId));
    }

    // ---------------- Cancel Booking ----------------
    @Transactional
    public Booking cancelBooking(UUID bookingId, UUID hallId, String reason) {
        return bookingRepository.findById(bookingId).map(b -> {
            b.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(b);

            List<String> seats = b.getItems().stream()
                    .flatMap(item -> item.getSeatIds().stream())
                    .toList();

            // Release in ShowService + Redis
            updateSeatsInShowService("/shows/release-seats", b.getShowId(), hallId, seats);
            // updateRedis(b.getShowId(), seats, "release");

            meterRegistry.counter("eventura.bookings.cancelled").increment();
            log.info("❌ Booking cancelled | bookingId={} | reason={}", b.getId(), reason);

            return b;
        }).orElseThrow(() -> new IllegalArgumentException("Booking not found with id " + bookingId));
    }

    // ---------------- Payment Success ----------------
    @Transactional
    public Booking markPaymentSuccess(UUID bookingId, UUID hallId) {
        log.info("💰 Marking payment success for bookingId={} | hallId={}", bookingId, hallId);
        return confirmBooking(bookingId, hallId);
    }

    // ---------------- Lock Seats ----------------
    @Transactional
    public boolean lockSeats(UUID showId, List<String> seatIds, UUID userId) {
        boolean success = seatLockService.tryLockSeats(showId.toString(), seatIds, userId.toString());
        if (success) {
            updateRedis(showId, seatIds, "lock");
            log.info("🔒 Locally locked seats in Redis | showId={} | seats={}", showId, seatIds);
        }
        return success;
    }

    @Transactional
    public int cancelStalePendingBookings(OffsetDateTime cutoffTime) {
        List<Booking> staleBookings = bookingRepository
                .findByStatusAndCreatedAtBefore(BookingStatus.PENDING, cutoffTime);

        log.info("🕒 Found {} stale pending bookings before {}", staleBookings.size(), cutoffTime);

        for (Booking b : staleBookings) {
            b.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(b);

            List<String> seats = b.getItems().stream()
                    .flatMap(item -> item.getSeatIds().stream())
                    .toList();

            seatLockService.releaseLocks(b.getShowId().toString(), seats, b.getUserId().toString());

            // Release seats in ShowService + Redis
            updateSeatsInShowService("/shows/release-seats", b.getShowId(), null, seats);
            updateRedis(b.getShowId(), seats, "release");

            log.info("❌ Auto-cancelled stale booking | bookingId={} | userId={}", b.getId(), b.getUserId());
        }

        meterRegistry.counter("eventura.bookings.auto_cancelled").increment(staleBookings.size());
        return staleBookings.size();
    }

    // ---------------- Helper: ShowService Call ----------------
    private void updateSeatsInShowService(String endpoint, UUID showId, UUID hallId, List<String> seats) {
        Map<String, Object> req = new HashMap<>();
        req.put("showId", showId);
        req.put("seats", seats);
        req.put("hallId", hallId);

        try {
            log.info("📤 Sending to ShowService {}: {}", endpoint, req);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(req, headers);

            restTemplate.exchange(
                    showServiceUrl + endpoint,
                    HttpMethod.POST,
                    entity,
                    Void.class
            );
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("⚠️ Seat conflict in ShowService [{}]: {}", endpoint, e.getMessage());
            throw new IllegalStateException("Seats already locked/booked in ShowService", e);
        } catch (Exception e) {
            log.error("❌ Failed to update ShowService [{}]: {}", endpoint, e.getMessage(), e);
            throw new IllegalStateException("Failed to update ShowService: " + e.getMessage(), e);
        }
    }

    // ---------------- Helper: Redis Update ----------------
    @SuppressWarnings("unchecked")
    private void updateRedis(UUID showId, List<String> seats, String action) {
        String key = redisKey(showId);
        HashOperations<String, String, Set<String>> ops = redisTemplate.opsForHash();

        Set<String> available = (Set<String>) ops.get(key, "available");
        Set<String> locked = (Set<String>) ops.get(key, "locked");
        Set<String> booked = (Set<String>) ops.get(key, "booked");

        if (available == null) available = new HashSet<>();
        if (locked == null) locked = new HashSet<>();
        if (booked == null) booked = new HashSet<>();

        switch (action) {
            case "lock" -> {
                available.removeAll(seats);
                locked.addAll(seats);
                log.info("🔒 Redis update: Locked seats={} for show={}", seats, showId);
            }
            case "confirm" -> {
                locked.removeAll(seats);
                booked.addAll(seats);
                log.info("✅ Redis update: Confirmed seats={} for show={}", seats, showId);
            }
            case "release" -> {
                locked.removeAll(seats);
                available.addAll(seats);
                log.info("♻️ Redis update: Released seats={} for show={}", seats, showId);
            }
        }

        ops.put(key, "available", available);
        ops.put(key, "locked", locked);
        ops.put(key, "booked", booked);
    }

    // ---------------- Misc ----------------
    public void notifySeatStatusChange(UUID showId, UUID hallId, List<String> seatIds, String status) {
        try {
            String url = showServiceUrl + "/seats/status";
            SeatStatusUpdateRequest payload = new SeatStatusUpdateRequest(showId, hallId, seatIds, status);
            restTemplate.postForEntity(url, payload, Void.class);
            log.info("✅ Seat status updated in ShowService: show={} hall={} seats={} status={}",
                    showId, hallId, seatIds, status);
        } catch (Exception e) {
            log.error("❌ Failed to notify ShowService: {}", e.getMessage(), e);
        }
    }

    public List<UserBookingResponse> getBookingsForUser(UUID userId) {
        log.info("Fetching bookings for user: {}", userId);
        List<Booking> bookings = bookingRepository.findByUserId(userId);
        log.debug("Found {} bookings for user {}", bookings.size(), userId);
        return bookings.stream().map(this::enrichAndMap).collect(Collectors.toList());
    }

    private UserBookingResponse enrichAndMap(Booking booking) {
        log.info("Enriching booking: {}", booking.getId());
        UserBookingResponse resp = UserBookingMapper.toResponse(booking);
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildAuthHeaders());

            String showUrl = showServiceBaseUrl + "/shows/" + booking.getShowId();
            log.info("Calling Show Service for booking {} -> {}", booking.getId(), showUrl);
            ResponseEntity<ShowDto> showResp = restTemplate.exchange(showUrl, HttpMethod.GET, entity, ShowDto.class);
            ShowDto show = showResp.getBody();
            if (show != null) {
                resp.setTime(show.getStartTime());
                if (show.getMovieId() != null) {
                    String movieUrl = catalogServiceBaseUrl + "/catalog/movies/" + show.getMovieId();
                    log.info("Calling Catalog Service (movie) for booking {} -> {}", booking.getId(), movieUrl);
                    ResponseEntity<MovieDto> movieResp = restTemplate.exchange(movieUrl, HttpMethod.GET, entity, MovieDto.class);
                    if (movieResp.getBody() != null) resp.setMovieTitle(movieResp.getBody().getTitle());
                }
            }

            if (booking.getHallId() != null) {
                String hallUrl = catalogServiceBaseUrl + "/catalog/halls/" + booking.getHallId();
                log.info("Calling Catalog Service (hall) for booking {} -> {}", booking.getId(), hallUrl);
                ResponseEntity<HallDto> hallResp = restTemplate.exchange(hallUrl, HttpMethod.GET, entity, HallDto.class);
                HallDto hall = hallResp.getBody();
                if (hall != null) {
                    resp.setHallName(hall.getName());
                    resp.setHallName(hall.getName());

                    if (hall.getTheaterId() != null) {
                        String theaterUrl = catalogServiceBaseUrl + "/catalog/theatres/" + hall.getTheaterId();
                        log.info("Calling Catalog Service (theatre) for booking {} -> {}", booking.getId(), theaterUrl);

                        ResponseEntity<TheaterDto> theaterResp = restTemplate.exchange(
                                theaterUrl,
                                HttpMethod.GET,
                                entity,
                                TheaterDto.class
                        );
                        TheaterDto theater = theaterResp.getBody();
                        log.debug("Fetched theater for booking {}: {}", booking.getId(), theater);

                        if (theater != null) {
                            resp.setLocation(
                                    theater.getName() +
                                            (theater.getCity() != null ? ", " + theater.getCity() : "")
                            );
                        }
                    }
                }
            }

        } catch (Exception ex) {
            log.warn("Could not enrich booking {}: {}", booking.getId(), ex.getMessage(), ex);
        }
        return resp;
    }

    private HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null) {
                headers.set("Authorization", authHeader);
                log.debug("Propagating Authorization header");
            }
        }
        return headers;
    }

    public BookingStatus getBookingStatus(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .map(Booking::getStatus)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found with id " + bookingId));
    }
}
