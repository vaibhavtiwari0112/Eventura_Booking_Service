package com.eventura.booking.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class CatalogClient {

    private final RestTemplate restTemplate;
    private final String catalogUrl;

    public CatalogClient(RestTemplate restTemplate,
                         @Value("${catalog.url:http://localhost:8083}") String catalogUrl) {
        this.restTemplate = restTemplate;
        this.catalogUrl = catalogUrl;
    }

    public List<String> fetchSeatsForHall(UUID hallId) {
        String url = catalogUrl + "/catalog/halls/" + hallId + "/seats";
        String[] seats = restTemplate.getForObject(url, String[].class);
        return seats != null ? Arrays.asList(seats) : List.of();
    }
}
