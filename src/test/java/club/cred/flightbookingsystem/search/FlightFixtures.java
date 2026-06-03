package club.cred.flightbookingsystem.search;

import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.Flight;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/** Helper for building {@link Flight} fixtures in tests. */
final class FlightFixtures {

    private static final Aircraft AIRCRAFT = new Aircraft("A320", 180);

    private FlightFixtures() {
    }

    static Flight flight(String source, String destination,
                         LocalDateTime departure, int durationMin,
                         int seatsRemaining, double fare) {
        LocalDateTime arrival = departure.plus(Duration.ofMinutes(durationMin));
        return new Flight(AIRCRAFT, source, destination, departure, arrival,
                durationMin, AIRCRAFT.getTotalSeats(), seatsRemaining, BigDecimal.valueOf(fare));
    }
}

