package club.cred.flightbookingsystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * True end-to-end integration test of the asynchronous Kafka path.
 *
 * <p>Unlike {@link BookingFlowIntegrationTest} (which simulates the payment callback by calling the
 * service directly), this test runs an in-JVM Kafka broker so the full async pipeline is exercised:
 * <ol>
 *   <li>the payment simulator publishes to {@code payment.callback};</li>
 *   <li>{@code PaymentCallbackConsumer} consumes it and confirms the booking → {@code SUCCESS};</li>
 *   <li>cancellation publishes to {@code payment.refund};</li>
 *   <li>{@code RefundProcessorStub} consumes it and completes the refund → {@code COMPLETED}.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("itkafka")
@EmbeddedKafka(partitions = 1, topics = {"payment.callback", "payment.refund", "booking.events"})
class BookingRefundKafkaE2EIntegrationTest {

    private static final LocalDate TRAVEL_DATE = LocalDate.of(2026, 7, 1);
    private static final Pattern BOOKING_ID = Pattern.compile("\"bookingId\":(\\d+)");

    @LocalServerPort private int port;

    @Autowired private AircraftRepository aircraftRepository;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private FlightGraph flightGraph;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void paymentCallbackAndRefund_flowEndToEndOverKafka() throws Exception {
        // --- seed a single direct flight and make it searchable ---
        bookingRepository.deleteAll();
        refundRepository.deleteAll();
        flightRepository.deleteAll();
        aircraftRepository.deleteAll();

        Aircraft aircraft = aircraftRepository.save(new Aircraft("A320", 180));
        LocalDateTime departure = TRAVEL_DATE.atTime(8, 0);
        Flight flight = flightRepository.save(new Flight(
                aircraft, "DEL", "BLR", departure, departure.plusHours(2),
                120, 180, 10, BigDecimal.valueOf(5000)));
        long flightId = flight.getId();
        flightGraph.refresh();

        // --- create booking (PENDING_PAYMENT) ---
        String body = "{\"flightIds\":[" + flightId + "],"
                + "\"passengers\":[{\"name\":\"Asha\",\"age\":30},{\"name\":\"Ravi\",\"age\":28}]}";
        HttpResponse<String> created = post("/api/v1/bookings", body);
        assertThat(created.statusCode()).isEqualTo(201);
        Matcher m = BOOKING_ID.matcher(created.body());
        assertThat(m.find()).isTrue();
        long bookingId = Long.parseLong(m.group(1));

        // --- publish payment.callback(success=true) via the simulator endpoint ---
        HttpResponse<String> callback = post(
                "/api/v1/_sim/payment-callback?bookingId=" + bookingId + "&success=true", null);
        assertThat(callback.statusCode()).isEqualTo(202);

        // consumer drives PENDING_PAYMENT -> SUCCESS asynchronously
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> stateOf(bookingId) == BookingState.SUCCESS);

        // --- cancel -> publishes payment.refund; stub completes it ---
        HttpResponse<String> cancel = post("/api/v1/bookings/" + bookingId + "/cancel", null);
        assertThat(cancel.statusCode()).isEqualTo(200);
        assertThat(stateOf(bookingId)).isEqualTo(BookingState.CANCELLED);

        // refund starts INITIATED then the (black-box) processor moves it to COMPLETED over Kafka
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> refundRepository.findAll().size() == 1
                        && refundRepository.findAll().get(0).getState() == RefundState.COMPLETED);

        assertThat(refundRepository.findAll().get(0).getBookingId()).isEqualTo(bookingId);
        // seats released back to the original 10 after cancellation
        assertThat(flightRepository.findById(flightId).orElseThrow().getSeatsRemaining()).isEqualTo(10);
    }

    private HttpResponse<String> post(String path, String reqBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        builder = (reqBody == null)
                ? builder.POST(HttpRequest.BodyPublishers.noBody())
                : builder.POST(HttpRequest.BodyPublishers.ofString(reqBody));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private BookingState stateOf(long bookingId) {
        return bookingRepository.findById(bookingId).map(b -> b.getState()).orElse(null);
    }
}

