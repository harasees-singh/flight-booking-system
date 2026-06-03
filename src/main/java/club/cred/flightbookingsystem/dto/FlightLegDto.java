package club.cred.flightbookingsystem.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A single leg (direct flight) within a journey. */
public record FlightLegDto(
        Long flightId,
        String source,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        int flightDurationMin,
        int seatsRemaining,
        String aircraftModel,
        BigDecimal baseFare) {
}

