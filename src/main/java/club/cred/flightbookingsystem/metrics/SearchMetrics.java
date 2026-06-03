package club.cred.flightbookingsystem.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Custom Micrometer instrumentation for the search read path.
 *
 * <p>Exposes a counter tagged by route ({@code src}, {@code dst}) and {@code outcome}
 * (hit / miss) so Grafana can surface <b>search query patterns</b> — the most-searched
 * routes and the fraction of searches that return no journeys. Cardinality is bounded by
 * the (small) number of city codes, so the route tags are safe to publish.
 */
@Component
public class SearchMetrics {

    private static final String SEARCH_REQUESTS = "flightbooking.search.requests";

    private final MeterRegistry registry;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Record a single search, tagging the route and whether any journey was found. */
    public void recordSearch(String source, String destination, int journeysFound) {
        Counter.builder(SEARCH_REQUESTS)
                .description("Flight search requests by route and outcome")
                .tag("src", source)
                .tag("dst", destination)
                .tag("outcome", journeysFound > 0 ? "hit" : "miss")
                .register(registry)
                .increment();
    }
}

