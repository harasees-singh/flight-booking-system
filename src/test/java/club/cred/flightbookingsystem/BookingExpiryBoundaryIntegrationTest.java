package club.cred.flightbookingsystem;

import static org.assertj.core.api.Assertions.assertThat;

import club.cred.flightbookingsystem.booking.BookingExpirySweeper;
import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.repository.AircraftRepository;
import club.cred.flightbookingsystem.repository.BookingRepository;
import club.cred.flightbookingsystem.repository.FlightRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sweeper boundary / TTL behaviour with a realistic (non-zero) payment TTL.
 *
 * <p>Complements {@link BookingFlowIntegrationTest} (which uses TTL=0 so everything is immediately
 * stale). Here the TTL is 1 hour, verifying that the sweeper:
 * <ul>
 *   <li>does <b>not</b> expire a freshly created booking that is still within the TTL window;</li>
 *   <li><b>does</b> expire a booking whose creation time has aged past the TTL, releasing seats.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "flightbooking.seed.flight-count=0",
        "flightbooking.booking.payment-ttl-seconds=3600" // 1-hour TTL
})
class BookingExpiryBoundaryIntegrationTest {

    private static final LocalDate TRAVEL_DATE = LocalDate.of(2026, 7, 1);
    private static final Pattern BOOKING_ID = Pattern.compile("\"bookingId\":(\\d+)");

    @LocalServerPort private int port;

    @Autowired private AircraftRepository aircraftRepository;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingExpirySweeper expirySweeper;

    private final HttpClient http = HttpClient.newHttpClient();
    private Long flightId;

    @BeforeEach
    void seed() {
        bookingRepository.deleteAll();
        flightRepository.deleteAll();
        aircraftRepository.deleteAll();

        Aircraft aircraft = aircraftRepository.save(new Aircraft("A320", 180));
        LocalDateTime departure = TRAVEL_DATE.atTime(8, 0);
        flightId = flightRepository.save(new Flight(
                aircraft, "DEL", "BLR", departure, departure.plusHours(2),
                120, 180, 6, BigDecimal.valueOf(5000))).getId();
    }

    @Test
    void freshBookingWithinTtl_isNotExpiredBySweeper() throws Exception {
        long bookingId = createBooking();
        assertThat(seats()).isEqualTo(4);

        // Created moments ago — well within the 1-hour TTL, so the sweep must leave it alone.
        expirySweeper.sweepExpiredBookings();

        assertThat(stateOf(bookingId)).isEqualTo(BookingState.PENDING_PAYMENT);
        assertThat(seats()).isEqualTo(4); // seats still blocked
    }

    @Test
    void bookingAgedPastTtl_isExpiredBySweeperAndSeatsReleased() throws Exception {
        long bookingId = createBooking();
        assertThat(seats()).isEqualTo(4);

        // Backdate creation time to 2 hours ago so it is now older than the 1-hour TTL.
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        ReflectionTestUtils.setField(booking, "createdAt", LocalDateTime.now().minusHours(2));
        bookingRepository.saveAndFlush(booking);

        expirySweeper.sweepExpiredBookings();

        assertThat(stateOf(bookingId)).isEqualTo(BookingState.FAILURE);
        assertThat(seats()).isEqualTo(6); // released
    }

    private long createBooking() throws Exception {
        String body = "{\"flightIds\":[" + flightId + "],"
                + "\"passengers\":[{\"name\":\"A\",\"age\":30},{\"name\":\"B\",\"age\":31}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        Matcher m = BOOKING_ID.matcher(response.body());
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private BookingState stateOf(long bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow().getState();
    }

    private int seats() {
        return flightRepository.findById(flightId).orElseThrow().getSeatsRemaining();
    }
}

