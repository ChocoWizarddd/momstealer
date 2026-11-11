package com.nightlight.app.data.api.dto;

public final class WeatherDtos {
    private WeatherDtos() {
    }

    public static final class WeatherDto {
        public String condition; // SUNNY | CLOUDY | PARTLY_CLOUDY | RAIN | ... | UNKNOWN
        public String label;
        public Double tempC;
        public boolean isDay;
        public String city;
        public long fetchedAt;
    }
}