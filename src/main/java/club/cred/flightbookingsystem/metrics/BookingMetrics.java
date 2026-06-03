package club.cred.flightbookingsystem.metrics;

import club.cred.flightbookingsystem.domain.BookingState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Custom Micrometer instrumentation for the booking write path.
 *
 * <p>Publishes a counter of booking <b>state transitions</b> tagged by target {@link BookingState}
 * (so Grafana can chart confirmations vs. failures vs. cancellations) and a counter of bookings
 * <b>rejected for lack of seats</b>. These complement the auto-instrumented HTTP and Kafka meters.
 */
@Component
public class BookingMetrics {

    private static final String TRANSITIONS = "flightbooking.booking.transitions";
    private static final String SEAT_REJECTIONS = "flightbooking.booking.seat_rejections";

    private final MeterRegistry registry;

    public BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Record a booking moving into {@code state} (e.g. PENDING_PAYMENT, SUCCESS, FAILURE, CANCELLED). */
    public void recordTransition(BookingState state) {
        Counter.builder(TRANSITIONS)
                .description("Booking state transitions by target state")
                .tag("state", state.name())
                .register(registry)
                .increment();
    }

    /** Record a booking request rejected because a leg had insufficient seats remaining. */
    public void recordSeatRejection() {
        Counter.builder(SEAT_REJECTIONS)
                .description("Booking requests rejected due to insufficient seats")
                .register(registry)
                .increment();
    }
}

