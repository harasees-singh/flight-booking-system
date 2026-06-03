package club.cred.flightbookingsystem.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import club.cred.flightbookingsystem.domain.BookingState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BookingMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BookingMetrics metrics = new BookingMetrics(registry);

    @Test
    void recordTransitionIncrementsCounterTaggedByState() {
        metrics.recordTransition(BookingState.SUCCESS);
        metrics.recordTransition(BookingState.SUCCESS);
        metrics.recordTransition(BookingState.FAILURE);

        assertThat(counterCount("flightbooking.booking.transitions", "state", "SUCCESS")).isEqualTo(2.0);
        assertThat(counterCount("flightbooking.booking.transitions", "state", "FAILURE")).isEqualTo(1.0);
    }

    @Test
    void recordSeatRejectionIncrementsCounter() {
        metrics.recordSeatRejection();
        metrics.recordSeatRejection();

        double count = registry.get("flightbooking.booking.seat_rejections").counter().count();
        assertThat(count).isEqualTo(2.0);
    }

    private double counterCount(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }
}

