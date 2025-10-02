package com.eventura.booking.dto;

import java.util.UUID;

public class HallDto {
    private UUID id;
    private UUID theaterId;
    private String name;
    // getters/setters
    public UUID getId(){return id;} public void setId(UUID id){this.id=id;}
    public UUID getTheaterId(){return theaterId;} public void setTheaterId(UUID t){this.theaterId=t;}
    public String getName(){return name;} public void setName(String n){this.name=n;}
}



