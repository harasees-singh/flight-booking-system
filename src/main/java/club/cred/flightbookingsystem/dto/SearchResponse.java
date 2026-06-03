package club.cred.flightbookingsystem.dto;

import java.time.LocalDate;
import java.util.List;

/** Response for a flight search query. */
public record SearchResponse(
        String source,
        String destination,
        LocalDate date,
        int passengers,
        int journeyCount,
        List<JourneyDto> journeys) {
}

