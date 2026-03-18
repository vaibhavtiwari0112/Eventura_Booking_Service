package com.eventura.booking.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// SeatLockService.java — wrap tryLockSeats with resilience

@Service
public class SeatLockService {

    private static final Logger log = LoggerFactory.getLogger(SeatLockService.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${eventura.booking.seat-lock-ttl-seconds:120}")
    private long lockTtlSeconds;

    public SeatLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String lockKey(String showId, String seatId) {
        return "seat:lock:" + showId + ":" + seatId;
    }

    /**
     * Atomically locks ALL seats or none.
     * On Redis failure: logs the error and returns true (fail-open) so booking
     * can proceed via ShowService as the fallback source of truth.
     *
     * Change failOpen=false if you want strict Redis-required behaviour.
     */
    public boolean tryLockSeats(String showId, List<String> seatIds, String userId) {
        List<String> acquired = new ArrayList<>();
        try {
            for (String seat : seatIds) {
                String key = lockKey(showId, seat);
                Boolean success = redisTemplate.opsForValue()
                        .setIfAbsent(key, userId, Duration.ofSeconds(lockTtlSeconds));

                if (Boolean.TRUE.equals(success)) {
                    acquired.add(seat);
                } else {
                    // Another user holds this seat — rollback and reject
                    releaseLocks(showId, acquired, userId);
                    log.warn("Seat already locked | showId={} seat={}", showId, seat);
                    return false;
                }
            }
            return true;

        } catch (RedisConnectionFailureException | RedisSystemException e) {
            // ✅ Redis is down or connection was reset
            // Release whatever we acquired before the failure
            safeReleaseLocks(showId, acquired, userId);

            log.error("Redis unavailable during seat lock | showId={} seats={} — failing open, " +
                    "ShowService will be source of truth: {}", showId, seatIds, e.getMessage());

            // ✅ Fail-open: let the booking proceed — ShowService will reject duplicates
            // This prevents Redis outages from blocking all bookings
            return true;
        }
    }

    /**
     * Releases only locks owned by this userId using atomic Lua script.
     */
    public void releaseLocks(String showId, List<String> seatIds, String userId) {
        if (seatIds == null || seatIds.isEmpty()) return;
        String luaScript =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('del', KEYS[1]) " +
                        "else return 0 end";

        for (String seat : seatIds) {
            try {
                redisTemplate.execute(
                        new DefaultRedisScript<>(luaScript, Long.class),
                        List.of(lockKey(showId, seat)),
                        userId
                );
            } catch (Exception e) {
                // Log but don't throw — TTL will clean up the key anyway
                log.warn("Could not release Redis lock for seat={} — TTL will expire it: {}",
                        seat, e.getMessage());
            }
        }
    }

    /**
     * Silent release — used in error paths where we must not throw.
     */
    private void safeReleaseLocks(String showId, List<String> seatIds, String userId) {
        try {
            releaseLocks(showId, seatIds, userId);
        } catch (Exception e) {
            log.warn("safeRelease failed (ignored) | seats={}: {}", seatIds, e.getMessage());
        }
    }

    public List<String> getSeatsLockedByUser(String showId, String userId) {
        try {
            Set<String> keys = redisTemplate.keys("seat:lock:" + showId + ":*");
            if (keys == null) return List.of();
            return keys.stream()
                    .filter(k -> userId.equals(redisTemplate.opsForValue().get(k)))
                    .map(k -> k.substring(k.lastIndexOf(':') + 1))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Redis unavailable for getSeatsLockedByUser: {}", e.getMessage());
            return List.of();
        }
    }

    public List<String> getAllLockedSeatsForShow(String showId) {
        try {
            Set<String> keys = redisTemplate.keys("seat:lock:" + showId + ":*");
            if (keys == null) return List.of();
            return keys.stream()
                    .map(k -> k.substring(k.lastIndexOf(':') + 1))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Redis unavailable for getAllLockedSeats: {}", e.getMessage());
            return List.of();
        }
    }
}