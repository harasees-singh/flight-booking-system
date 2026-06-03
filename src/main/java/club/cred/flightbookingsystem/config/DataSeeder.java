package club.cred.flightbookingsystem.config;

import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.repository.AircraftRepository;
import club.cred.flightbookingsystem.repository.FlightRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the database with aircraft models and ~2000–3000 direct flights so the
 * search graph has realistic data. Runs only when the flight table is empty.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** Airport / city codes (single airline network). */
    private static final String[] CITIES = {
            "DEL", "BOM", "BLR", "MAA", "HYD", "CCU",
            "PNQ", "AMD", "GOI", "COK", "JAI", "LKO"
    };

    private final AircraftRepository aircraftRepository;
    private final FlightRepository flightRepository;
    private final int flightCount;
    private final int daysAhead;
    private final long seed;

    public DataSeeder(AircraftRepository aircraftRepository,
                      FlightRepository flightRepository,
                      @Value("${flightbooking.seed.flight-count:2500}") int flightCount,
                      @Value("${flightbooking.seed.days-ahead:14}") int daysAhead,
                      @Value("${flightbooking.seed.random-seed:42}") long seed) {
        this.aircraftRepository = aircraftRepository;
        this.flightRepository = flightRepository;
        this.flightCount = flightCount;
        this.daysAhead = daysAhead;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (flightRepository.count() > 0) {
            log.info("Flights already present ({}), skipping seed.", flightRepository.count());
            return;
        }

        List<Aircraft> aircrafts = aircraftRepository.saveAll(List.of(
                new Aircraft("A320", 180),
                new Aircraft("A321", 220),
                new Aircraft("B737", 189),
                new Aircraft("B777", 396),
                new Aircraft("ATR72", 72)
        ));

        Random random = new Random(seed);
        LocalDate startDate = LocalDate.now();
        List<Flight> flights = new ArrayList<>(flightCount);

        for (int i = 0; i < flightCount; i++) {
            int srcIdx = random.nextInt(CITIES.length);
            int dstIdx = random.nextInt(CITIES.length);
            if (dstIdx == srcIdx) {
                dstIdx = (dstIdx + 1) % CITIES.length;
            }
            String source = CITIES[srcIdx];
            String destination = CITIES[dstIdx];

            Aircraft aircraft = aircrafts.get(random.nextInt(aircrafts.size()));
            int totalSeats = aircraft.getTotalSeats();
            // Approximate current availability between 30% and 100% of capacity.
            int seatsRemaining = (int) (totalSeats * (0.3 + 0.7 * random.nextDouble()));

            LocalDate day = startDate.plusDays(random.nextInt(daysAhead));
            int departHour = 5 + random.nextInt(18); // 05:00–22:00
            int departMinute = random.nextInt(4) * 15;
            LocalDateTime departure = LocalDateTime.of(day, LocalTime.of(departHour, departMinute));

            int durationMin = 60 + random.nextInt(180); // 1h–4h
            LocalDateTime arrival = departure.plusMinutes(durationMin);

            BigDecimal baseFare = BigDecimal.valueOf(2500 + durationMin * (8 + random.nextInt(12)))
                    .setScale(2, RoundingMode.HALF_UP);

            flights.add(new Flight(aircraft, source, destination, departure, arrival,
                    durationMin, totalSeats, seatsRemaining, baseFare));
        }

        flightRepository.saveAll(flights);
        log.info("Seeded {} aircraft and {} flights across {} cities.",
                aircrafts.size(), flights.size(), CITIES.length);
    }
}

