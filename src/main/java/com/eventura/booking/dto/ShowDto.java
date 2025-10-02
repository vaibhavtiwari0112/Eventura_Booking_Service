package com.eventura.booking.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// Minimal DTOs matching the responses of other services (adjust fields as needed)
public  class ShowDto {
    private UUID id;
    private UUID movieId;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private BigDecimal basePrice;
    // getters/setters
    public UUID getId(){return id;} public void setId(UUID id){this.id=id;}
    public UUID getMovieId(){return movieId;} public void setMovieId(UUID m){this.movieId=m;}
    public OffsetDateTime getStartTime(){return startTime;} public void setStartTime(OffsetDateTime t){this.startTime=t;}
    public OffsetDateTime getEndTime(){return endTime;} public void setEndTime(OffsetDateTime t){this.endTime=t;}
    public BigDecimal getBasePrice(){return basePrice;} public void setBasePrice(BigDecimal p){this.basePrice=p;}
}

