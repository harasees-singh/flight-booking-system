package club.cred.flightbookingsystem.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A journey from source to destination: 1..3 ordered legs.
 * {@code totalFarePerPassenger} = sum of each leg's base fare (flat per passenger).
 */
public record JourneyDto(
        List<FlightLegDto> legs,
        int stops,
        int totalDurationMin,
        BigDecimal totalFarePerPassenger) {
}

