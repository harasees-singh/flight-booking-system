package club.cred.flightbookingsystem;

import static org.assertj.core.api.Assertions.assertThat;

import club.cred.flightbookingsystem.booking.BookingExpirySweeper;
import club.cred.flightbookingsystem.booking.BookingService;
import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.domain.Refund;
import club.cred.flightbookingsystem.domain.RefundState;
import club.cred.flightbookingsystem.repository.AircraftRepository;
import club.cred.flightbookingsystem.repository.BookingRepository;
import club.cred.flightbookingsystem.repository.FlightRepository;
import club.cred.flightbookingsystem.repository.RefundRepository;
import club.cred.flightbookingsystem.search.FlightGraph;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Full-stack integration tests for the booking and refund flows.
 *
 * <p>Boots the real application on a random port against in-memory H2 (the {@code local} profile,
 * Kafka disabled) and drives the actual HTTP endpoints over the wire with the JDK {@link HttpClient},
 * exercising the real controllers → services → repositories and asserting persisted state (booking
 * state machine, seat inventory, refunds). The asynchronous payment callback — which normally arrives
 * over Kafka — is simulated by invoking {@link BookingService#confirmPayment} directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "flightbooking.seed.flight-count=0",          // no random seed data; we build deterministic flights
        "flightbooking.booking.payment-ttl-seconds=0" // any PENDING_PAYMENT is immediately sweepable
})
class BookingFlowIntegrationTest {

    private static final LocalDate TRAVEL_DATE = LocalDate.of(2026, 7, 1);
    private static final BigDecimal FARE = BigDecimal.valueOf(5000);
    private static final Pattern BOOKING_ID = Pattern.compile("\"bookingId\":(\\d+)");

    @LocalServerPort private int port;

    @Autowired private AircraftRepository aircraftRepository;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private FlightGraph flightGraph;
    @Autowired private BookingService bookingService;
    @Autowired private BookingExpirySweeper expirySweeper;

    private final HttpClient http = HttpClient.newHttpClient();
    private Long flightId;

    @BeforeEach
    void seedSingleDirectFlight() {
        // Clean slate (order matters: bookings hold the booking_flight join, flights reference aircraft).
        bookingRepository.deleteAll();
        refundRepository.deleteAll();
        flightRepository.deleteAll();
        aircraftRepository.deleteAll();

        Aircraft aircraft = aircraftRepository.save(new Aircraft("A320", 180));
        LocalDateTime departure = TRAVEL_DATE.atTime(8, 0);
        Flight flight = flightRepository.save(new Flight(
                aircraft, "DEL", "BLR", departure, departure.plusHours(2),
                120, 180, /* seatsRemaining */ 6, FARE));
        this.flightId = flight.getId();

        // Make the freshly-seeded flight visible to the in-memory search graph.
        flightGraph.refresh();
    }

    // ---- booking flow ------------------------------------------------------

    @Test
    void fullHappyPath_searchCreateConfirmThenCancelWithRefund() throws Exception {
        // 1. Search surfaces the direct flight.
        HttpResponse<String> search = get("/api/v1/flights/search?src=DEL&dst=BLR&date="
                + TRAVEL_DATE + "&pax=2");
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(search.body()).contains("\"journeyCount\":1");
        assertThat(search.body()).contains("\"flightId\":" + flightId);

        // 2. Create booking -> PENDING_PAYMENT, seats blocked (6 -> 4).
        long bookingId = createBooking(2);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.PENDING_PAYMENT);
        assertThat(seatsRemaining()).isEqualTo(4);

        // 3. Payment confirmation (Kafka callback stand-in) -> SUCCESS.
        bookingService.confirmPayment(bookingId, true);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.SUCCESS);

        HttpResponse<String> fetched = get("/api/v1/bookings/" + bookingId);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).contains("\"state\":\"SUCCESS\"");

        // 4. Cancel -> CANCELLED, seats released (back to 6), partial refund raised.
        HttpResponse<String> cancel = post("/api/v1/bookings/" + bookingId + "/cancel", null);
        assertThat(cancel.statusCode()).isEqualTo(200);
        assertThat(cancel.body()).contains("\"state\":\"CANCELLED\"");
        assertThat(cancel.body()).contains("\"state\":\"INITIATED\""); // refund view

        assertThat(stateOf(bookingId)).isEqualTo(BookingState.CANCELLED);
        assertThat(seatsRemaining()).isEqualTo(6);

        List<Refund> refunds = refundRepository.findAll();
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getBookingId()).isEqualTo(bookingId);
        // 80% of (5000 * 2) = 8000.00
        assertThat(refunds.get(0).getAmount()).isEqualByComparingTo("8000.00");
        assertThat(refunds.get(0).getState()).isEqualTo(RefundState.INITIATED);
    }

    @Test
    void paymentDenied_movesBookingToFailureAndReleasesSeats() throws Exception {
        long bookingId = createBooking(2);
        assertThat(seatsRemaining()).isEqualTo(4);

        bookingService.confirmPayment(bookingId, false);

        assertThat(stateOf(bookingId)).isEqualTo(BookingState.FAILURE);
        assertThat(seatsRemaining()).isEqualTo(6); // seats released
    }

    @Test
    void stalePendingBooking_isExpiredBySweeperAndSeatsReleased() throws Exception {
        long bookingId = createBooking(2);
        assertThat(seatsRemaining()).isEqualTo(4);

        // TTL is 0 in this context, so the pending booking is already stale.
        expirySweeper.sweepExpiredBookings();

        assertThat(stateOf(bookingId)).isEqualTo(BookingState.FAILURE);
        assertThat(seatsRemaining()).isEqualTo(6);
    }

    @Test
    void overbooking_isRejectedWithConflictAndSeatsUntouched() throws Exception {
        // Only 6 seats remain; requesting 8 must be rejected atomically.
        HttpResponse<String> response = post("/api/v1/bookings", bookingBody(8));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(seatsRemaining()).isEqualTo(6); // nothing blocked
        assertThat(bookingRepository.count()).isZero();
    }

    @Test
    void cancellingNonConfirmedBooking_isRejected() throws Exception {
        long bookingId = createBooking(2); // still PENDING_PAYMENT

        HttpResponse<String> response = post("/api/v1/bookings/" + bookingId + "/cancel", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.PENDING_PAYMENT);
        assertThat(refundRepository.count()).isZero();
    }

    // ---- refund flow -------------------------------------------------------

    @Test
    void refundFlow_cancellationRaisesPartialRefundForConfirmedBooking() throws Exception {
        long bookingId = createBooking(3); // total = 5000 * 3 = 15000
        bookingService.confirmPayment(bookingId, true);

        HttpResponse<String> cancel = post("/api/v1/bookings/" + bookingId + "/cancel", null);
        assertThat(cancel.statusCode()).isEqualTo(200);

        Refund refund = refundRepository.findAll().get(0);
        // 80% of 15000 = 12000.00 refunded; 20% retained as cancellation fee.
        assertThat(refund.getAmount()).isEqualByComparingTo("12000.00");
        assertThat(refund.getState()).isEqualTo(RefundState.INITIATED);
        assertThat(refund.getBookingId()).isEqualTo(bookingId);
    }

    // ---- timeout / sweeper race-safety -------------------------------------

    @Test
    void latePaymentCallbackAfterExpiry_isIgnoredAndSeatsNotDoubleReleased() throws Exception {
        long bookingId = createBooking(2);
        assertThat(seatsRemaining()).isEqualTo(4);

        // Sweep first (TTL=0) -> FAILURE + seats released back to 6.
        expirySweeper.sweepExpiredBookings();
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.FAILURE);
        assertThat(seatsRemaining()).isEqualTo(6);

        // A payment confirmation that lands AFTER expiry must be a no-op (compare-and-set loses):
        // state stays FAILURE and seats are NOT incremented a second time.
        bookingService.confirmPayment(bookingId, true);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.FAILURE);
        assertThat(seatsRemaining()).isEqualTo(6);
    }

    @Test
    void sweeperOnlyTargetsPendingBookings_confirmedBookingUntouched() throws Exception {
        long bookingId = createBooking(2);
        bookingService.confirmPayment(bookingId, true); // -> SUCCESS, seats remain 4
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.SUCCESS);
        assertThat(seatsRemaining()).isEqualTo(4);

        // Even with TTL=0, the sweeper must only act on PENDING_PAYMENT, never SUCCESS.
        expirySweeper.sweepExpiredBookings();

        assertThat(stateOf(bookingId)).isEqualTo(BookingState.SUCCESS);
        assertThat(seatsRemaining()).isEqualTo(4); // seats not released
    }

    @Test
    void duplicatePaymentConfirmation_isIdempotent() throws Exception {
        long bookingId = createBooking(2);
        assertThat(seatsRemaining()).isEqualTo(4);

        bookingService.confirmPayment(bookingId, true);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.SUCCESS);

        // A redelivered/duplicate payment.callback must be a no-op: state and seats unchanged.
        bookingService.confirmPayment(bookingId, true);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.SUCCESS);
        assertThat(seatsRemaining()).isEqualTo(4);
    }

    // ---- concurrency / no-oversell -----------------------------------------

    @Test
    void concurrentBookings_neverOversellSeats() throws Exception {
        // 6 seats, each request asks for 2 -> at most 3 of the 10 racing requests can win.
        int concurrency = 10;
        int paxEach = 2;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            results.add(pool.submit(() -> {
                startGate.await(); // line everyone up to maximise contention
                return post("/api/v1/bookings", bookingBody(paxEach)).statusCode();
            }));
        }
        startGate.countDown();

        int created = 0;
        int conflict = 0;
        for (Future<Integer> result : results) {
            int code = result.get();
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Exactly 3 winners; the rest are rejected. Seats fully allocated, never negative.
        assertThat(created).isEqualTo(3);
        assertThat(conflict).isEqualTo(concurrency - 3);
        assertThat(seatsRemaining()).isZero();
        assertThat(bookingRepository.count()).isEqualTo(3);
    }

    // ---- connecting (multi-leg) journey ------------------------------------

    @Test
    void multiLegJourney_blocksAndReleasesEveryLeg() throws Exception {
        // Two connecting legs with a valid 90-minute layover: DEL->BOM (08:00-10:00), BOM->BLR (11:30-13:30).
        long legA = saveFlight("DEL", "BOM", 8, 0, 120, 5);
        long legB = saveFlight("BOM", "BLR", 11, 30, 120, 5);
        flightGraph.refresh();

        long bookingId = createBookingFor(legA + "," + legB, 2);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.PENDING_PAYMENT);
        assertThat(seatsOf(legA)).isEqualTo(3);
        assertThat(seatsOf(legB)).isEqualTo(3);

        bookingService.confirmPayment(bookingId, true);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.SUCCESS);

        HttpResponse<String> cancel = post("/api/v1/bookings/" + bookingId + "/cancel", null);
        assertThat(cancel.statusCode()).isEqualTo(200);

        assertThat(stateOf(bookingId)).isEqualTo(BookingState.CANCELLED);
        // every leg's seats are released back.
        assertThat(seatsOf(legA)).isEqualTo(5);
        assertThat(seatsOf(legB)).isEqualTo(5);
        // refund = 80% of (5000 + 5000) * 2 = 16000.00
        assertThat(refundRepository.findAll().get(0).getAmount()).isEqualByComparingTo("16000.00");
    }

    // ---- helpers -----------------------------------------------------------

    private long createBooking(int pax) throws Exception {
        return createBookingFor(String.valueOf(flightId), pax);
    }

    private long createBookingFor(String flightIdsCsv, int pax) throws Exception {
        HttpResponse<String> response = post("/api/v1/bookings", bookingBodyFor(flightIdsCsv, pax));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"state\":\"PENDING_PAYMENT\"");
        Matcher m = BOOKING_ID.matcher(response.body());
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private String bookingBody(int pax) {
        return bookingBodyFor(String.valueOf(flightId), pax);
    }

    private String bookingBodyFor(String flightIdsCsv, int pax) {
        StringBuilder passengers = new StringBuilder();
        for (int i = 0; i < pax; i++) {
            if (i > 0) {
                passengers.append(',');
            }
            passengers.append("{\"name\":\"P").append(i).append("\",\"age\":30}");
        }
        return "{\"flightIds\":[" + flightIdsCsv + "],\"passengers\":[" + passengers + "]}";
    }

    private long saveFlight(String src, String dst, int depHour, int depMin, int durationMin, int seats) {
        Aircraft aircraft = aircraftRepository.findAll().get(0);
        LocalDateTime departure = TRAVEL_DATE.atTime(depHour, depMin);
        Flight flight = flightRepository.save(new Flight(
                aircraft, src, dst, departure, departure.plusMinutes(durationMin),
                durationMin, 180, seats, FARE));
        return flight.getId();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json");
        builder = (body == null)
                ? builder.POST(HttpRequest.BodyPublishers.noBody())
                : builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private BookingState stateOf(long bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow().getState();
    }

    private int seatsRemaining() {
        return flightRepository.findById(flightId).orElseThrow().getSeatsRemaining();
    }

    private int seatsOf(long someFlightId) {
        return flightRepository.findById(someFlightId).orElseThrow().getSeatsRemaining();
    }
}

