package com.eventura.booking.dto;

import java.util.UUID;

public class MovieDto {
    private UUID id;
    private String title;
    // getters/setters
    public UUID getId(){return id;} public void setId(UUID id){this.id=id;}
    public String getTitle(){return title;} public void setTitle(String t){this.title=t;}
}
