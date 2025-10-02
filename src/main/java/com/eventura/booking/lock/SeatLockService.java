package com.eventura.booking.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class SeatLockService {
    private static final Logger log = LoggerFactory.getLogger(SeatLockService.class);

    private final StringRedisTemplate redisTemplate;

    public SeatLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String lockKey(String showId, String seatId) {
        return "lock:" + showId + ":" + seatId;
    }

    /**
     * Try to lock all requested seats for a user
     */
    public boolean tryLockSeats(String showId, List<String> seatIds, String userId) {
        log.info("🔒 Trying to lock seats | showId={} | userId={} | seats={}", showId, userId, seatIds);

        List<String> successfullyLocked = new ArrayList<>();

        for (String seatId : seatIds) {
            String key = lockKey(showId, seatId);

            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(key, userId, Duration.ofMinutes(5)); // 5 min lock TTL

            log.debug("Seat lock attempt | seatId={} | key={} | success={} | currentOwner={}",
                    seatId, key, success, redisTemplate.opsForValue().get(key));

            if (Boolean.FALSE.equals(success)) {
                String currentOwner = redisTemplate.opsForValue().get(key);
                log.warn("⚠️ Failed to lock seat | seatId={} | already locked by {}", seatId, currentOwner);

                // rollback only seats that were locked by THIS user in this attempt
                releaseLocks(showId, successfullyLocked, userId);
                return false;
            } else {
                successfullyLocked.add(seatId);
            }
        }

        log.info("✅ Successfully locked all seats | showId={} | userId={} | seats={}", showId, userId, seatIds);
        return true;
    }

    /**
     * Release locks only if owned by the given user
     */
    public void releaseLocks(String showId, List<String> seatIds, String userId) {
        log.info("🔓 Releasing locks | showId={} | userId={} | seats={}", showId, userId, seatIds);

        for (String seatId : seatIds) {
            String key = lockKey(showId, seatId);
            String owner = redisTemplate.opsForValue().get(key);

            if (userId.equals(owner)) {
                redisTemplate.delete(key);
                log.debug("Released lock | seatId={} | key={}", seatId, key);
            } else {
                log.warn("Skipped releasing seat | seatId={} | key={} | not owned by user {}", seatId, key, userId);
            }
        }
    }

    /**
     * Get all seats locked by a specific user for a show
     */
    public List<String> getSeatsLockedByUser(String showId, String userId) {
        Set<String> keys = redisTemplate.keys("lock:" + showId + ":*");
        if (keys == null) return List.of();

        return keys.stream()
                .filter(key -> userId.equals(redisTemplate.opsForValue().get(key)))
                .map(key -> key.substring(key.lastIndexOf(":") + 1)) // extract seatId
                .toList();
    }

    /**
     * Check if a specific seat is locked by a specific user
     */
    public boolean isSeatLockedBy(String showId, String seatId, String userId) {
        String key = lockKey(showId, seatId);
        String owner = redisTemplate.opsForValue().get(key);

        boolean result = userId.equals(owner);
        log.debug("Checking seat lock | showId={} | seatId={} | userId={} | lockedByUser={} | currentOwner={}",
                showId, seatId, userId, result, owner);

        return result;
    }

    /**
     * ✅ Get all currently locked seats for a show (regardless of user)
     */
    public List<String> getAllLockedSeatsForShow(String showId) {
        Set<String> keys = redisTemplate.keys("lock:" + showId + ":*");
        if (keys == null) return List.of();

        return keys.stream()
                .map(key -> key.substring(key.lastIndexOf(":") + 1)) // extract seatId
                .toList();
    }
}
