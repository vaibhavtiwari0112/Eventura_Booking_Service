package com.eventura.booking.cache;

import com.eventura.booking.dto.CatalogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private final RestTemplate restTemplate;
    private final String catalogUrl;

    public CatalogCache(RestTemplate restTemplate,
                        @Value("${catalog.url:http://localhost:8083}") String catalogUrl) {
        this.restTemplate = restTemplate;
        this.catalogUrl = catalogUrl;
    }

    // Key = hallId, Value = list of seatIds
    private final Map<UUID, List<String>> hallSeats = new ConcurrentHashMap<>();

    // Key = movieId, Value = movie title
    private final Map<UUID, String> movies = new ConcurrentHashMap<>();

    // Key = theaterId, Value = theater name
    private final Map<UUID, String> theaters = new ConcurrentHashMap<>();

    public void updateCache(CatalogEvent event) {
        log.info("📥 Received catalog event: type={} payload={}", event.getType(), event);

        try {
            switch (event.getType()) {
                case "movie.created" -> {
                    UUID movieId = event.getId();
                    movies.put(movieId, event.getTitle());
                    log.info("✅ Cached movie | id={} title={}", movieId, event.getTitle());
                }
                case "theater.created" -> {
                    UUID theaterId = event.getId();
                    theaters.put(theaterId, event.getName());
                    log.info("✅ Cached theater | id={} name={}", theaterId, event.getName());
                }
                case "hall.created" -> {
                    UUID hallId = event.getId();
                    List<String> seats = event.getSeats() != null ? event.getSeats() : List.of();
                    hallSeats.put(hallId, seats);
                    log.info("✅ Cached hall | id={} theaterId={} seats={}", hallId, event.getTheaterId(), seats);
                }
                default -> log.warn("⚠️ Unknown catalog event: {}", event);
            }
        } catch (IllegalArgumentException e) {
            log.error("❌ Failed to parse UUID from catalog event ID: {}", event.getId(), e);
        }
    }

    public boolean isSeatValid(UUID hallId, String seatId) {
        List<String> seats = getAllSeatsForHall(hallId);
        return seats.contains(seatId);
    }

    public Optional<String> getMovie(UUID movieId) {
        return Optional.ofNullable(movies.get(movieId));
    }

    public Set<UUID> getAllHallIds() {
        return hallSeats.keySet();
    }

    public List<String> getAllSeatsForHall(UUID hallId) {
        return hallSeats.computeIfAbsent(hallId, id -> {
            log.warn("⚠️ No seats found in cache for hallId={}, fetching from Catalog service...", id);
            try {
                String url = catalogUrl + "/catalog/halls/" + id + "/seats";
                String[] seats = restTemplate.getForObject(url, String[].class);
                List<String> seatList = seats != null ? Arrays.asList(seats) : List.of();
                log.info("✅ Loaded {} seats for hallId={} from Catalog service", seatList.size(), id);
                return seatList;
            } catch (Exception ex) {
                log.error("❌ Failed to fetch seats from Catalog service for hallId={}", id, ex);
                return List.of();
            }
        });
    }

    public void evictHall(UUID hallId) {
        hallSeats.remove(hallId);
        log.info("🗑️ Evicted hall cache | hallId={}", hallId);
    }
}
