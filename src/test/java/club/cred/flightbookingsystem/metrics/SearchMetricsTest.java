package club.cred.flightbookingsystem.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SearchMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SearchMetrics metrics = new SearchMetrics(registry);

    @Test
    void recordsHitWhenJourneysFound() {
        metrics.recordSearch("DEL", "BLR", 3);

        assertThat(outcomeCount("DEL", "BLR", "hit")).isEqualTo(1.0);
    }

    @Test
    void recordsMissWhenNoJourneysFound() {
        metrics.recordSearch("DEL", "BLR", 0);

        assertThat(outcomeCount("DEL", "BLR", "miss")).isEqualTo(1.0);
    }

    @Test
    void separatesCountersByRoute() {
        metrics.recordSearch("DEL", "BLR", 2);
        metrics.recordSearch("DEL", "BLR", 1);
        metrics.recordSearch("BOM", "MAA", 0);

        assertThat(outcomeCount("DEL", "BLR", "hit")).isEqualTo(2.0);
        assertThat(outcomeCount("BOM", "MAA", "miss")).isEqualTo(1.0);
    }

    private double outcomeCount(String src, String dst, String outcome) {
        return registry.get("flightbooking.search.requests")
                .tag("src", src).tag("dst", dst).tag("outcome", outcome)
                .counter().count();
    }
}

