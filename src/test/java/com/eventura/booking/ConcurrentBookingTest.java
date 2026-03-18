package com.eventura.booking;

import com.eventura.booking.domain.Booking;
import com.eventura.booking.domain.BookingItem;
import com.eventura.booking.domain.BookingStatus;
import com.eventura.booking.dto.CreateBookingRequest;
import com.eventura.booking.repository.BookingRepository;
import com.eventura.booking.service.BookingManageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = com.eventura.booking.BookingServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
//@ActiveProfiles("local")
public class ConcurrentBookingTest {

    @Autowired
    private TestRestTemplate testRestTemplate;   // ← for HTTP calls to our own app

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingManageService bookingManageService;

    // ✅ Mock the RestTemplate that calls ShowService / CatalogService
    // This prevents "Show not found" 500s when tests use random UUIDs
    @MockBean
    private RestTemplate restTemplate;

    @BeforeEach
    void mockExternalServices() {
        // Make ALL outbound RestTemplate calls (ShowService, CatalogService) return 200
        Mockito.when(restTemplate.exchange(
                Mockito.anyString(),
                Mockito.any(HttpMethod.class),
                Mockito.any(),
                Mockito.<Class<Void>>any()
        )).thenReturn(ResponseEntity.ok().build());

        // Also mock postForEntity used in notifySeatStatusChange
        Mockito.when(restTemplate.postForEntity(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.<Class<Void>>any()
        )).thenReturn(ResponseEntity.ok().build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Concurrent seat lock — only one user should win
    // Endpoint: POST /bookings/lock  @RequestBody CreateBookingRequest
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Only one user should successfully lock the same seat concurrently")
    void onlyOneUserShouldLockSameSeat() throws InterruptedException {
        int threadCount = 10;
        UUID showId = UUID.randomUUID();
        UUID hallId = UUID.randomUUID();
        String contestedSeat = "A1";

        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch allDone  = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);
        List<String> responses     = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startGun.await();

                    // ✅ Matches: POST /bookings/lock  @RequestBody CreateBookingRequest
                    CreateBookingRequest body = new CreateBookingRequest();
                    body.setUserId(UUID.randomUUID());   // each user is different
                    body.setShowId(showId);              // same show
                    body.setHallId(hallId);              // same hall
                    body.setSeatIds(List.of(contestedSeat)); // same contested seat
                    body.setAmount(500.0);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<CreateBookingRequest> request = new HttpEntity<>(body, headers);

                    ResponseEntity<String> resp = testRestTemplate.postForEntity(
                            "/bookings/lock", request, String.class);

                    String result = "User-" + idx + " → HTTP "
                            + resp.getStatusCode().value() + " | " + resp.getBody();
                    responses.add(result);

                    if (resp.getStatusCode().is2xxSuccessful()) successCount.incrementAndGet();
                    else failCount.incrementAndGet();

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    responses.add("User-" + idx + " → ERROR: " + e.getMessage());
                } finally {
                    allDone.countDown();
                }
            }).start();
        }

        startGun.countDown();
        allDone.await(15, TimeUnit.SECONDS);

        System.out.println("\n===== Seat lock concurrency results =====");
        responses.stream().sorted().forEach(System.out::println);
        System.out.println("Successes : " + successCount.get());
        System.out.println("Failures  : " + failCount.get());
        System.out.println("=========================================\n");

        // ✅ With Redis SETNX fix: exactly 1 wins, 9 get 409
        assertEquals(1, successCount.get(),
                "Expected exactly 1 success but got " + successCount.get()
                        + " — double booking detected!");
        assertEquals(threadCount - 1, failCount.get(),
                "Expected " + (threadCount - 1) + " rejections but got " + failCount.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Concurrent confirms on same booking
    // Calls service layer directly to isolate @Version optimistic lock test
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Concurrent confirms on same booking — only first should succeed")
    void concurrentConfirmSameBooking() throws InterruptedException {
        UUID bookingId = createTestBooking();
        UUID hallId    = UUID.randomUUID();

        int threadCount = 5;
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch allDone  = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> responses     = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startGun.await();

                    // ✅ Call service directly — ShowService is mocked via @MockBean RestTemplate
                    // This tests @Version optimistic locking in isolation
                    bookingManageService.confirmBooking(bookingId, hallId);
                    successCount.incrementAndGet();
                    responses.add("Thread-" + idx + " → SUCCESS");

                } catch (Exception e) {
                    // ObjectOptimisticLockingFailureException or IllegalStateException land here
                    responses.add("Thread-" + idx + " → REJECTED ("
                            + e.getClass().getSimpleName() + ")");
                } finally {
                    allDone.countDown();
                }
            }).start();
        }

        startGun.countDown();
        allDone.await(15, TimeUnit.SECONDS);

        System.out.println("\n===== Confirm concurrency results =====");
        responses.stream().sorted().forEach(System.out::println);
        System.out.println("Successes: " + successCount.get());
        System.out.println("=======================================\n");

        // ✅ With @Version: exactly 1 thread wins, rest get optimistic lock exception
        assertEquals(1, successCount.get(),
                "Only one confirm should succeed — @Version optimistic lock not working!");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Concurrent create bookings for the same seats
    // Endpoint: POST /bookings  @RequestBody CreateBookingRequest
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Concurrent full bookings for same seat — only one should be created")
    void concurrentCreateBookingSameSeat() throws InterruptedException {
        int threadCount = 5;
        UUID showId = UUID.randomUUID();
        UUID hallId = UUID.randomUUID();
        String contestedSeat = "B7";

        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch allDone  = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);
        List<String> responses     = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startGun.await();

                    // ✅ Matches: POST /bookings  @RequestBody CreateBookingRequest
                    CreateBookingRequest body = new CreateBookingRequest();
                    body.setUserId(UUID.randomUUID());
                    body.setShowId(showId);
                    body.setHallId(hallId);
                    body.setSeatIds(List.of(contestedSeat));
                    body.setAmount(500.0);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<CreateBookingRequest> request = new HttpEntity<>(body, headers);

                    ResponseEntity<String> resp = testRestTemplate.postForEntity(
                            "/bookings", request, String.class);

                    String result = "User-" + idx + " → HTTP "
                            + resp.getStatusCode().value() + " | " + resp.getBody();
                    responses.add(result);

                    if (resp.getStatusCode().is2xxSuccessful()) successCount.incrementAndGet();
                    else failCount.incrementAndGet();

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    responses.add("User-" + idx + " → ERROR: " + e.getMessage());
                } finally {
                    allDone.countDown();
                }
            }).start();
        }

        startGun.countDown();
        allDone.await(15, TimeUnit.SECONDS);

        System.out.println("\n===== Create booking concurrency results =====");
        responses.stream().sorted().forEach(System.out::println);
        System.out.println("Successes : " + successCount.get());
        System.out.println("Failures  : " + failCount.get());
        System.out.println("=============================================\n");

        assertEquals(1, successCount.get(),
                "Expected exactly 1 booking created but got " + successCount.get());
        assertEquals(threadCount - 1, failCount.get(),
                "Expected " + (threadCount - 1) + " rejections but got " + failCount.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────
    private UUID createTestBooking() {
        Booking b = new Booking(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.PENDING);
        b.setHallId(UUID.randomUUID());
        BookingItem item = new BookingItem(List.of("C3"), 500.0);
        b.addItem(item);
        return bookingRepository.save(b).getId();
    }
}