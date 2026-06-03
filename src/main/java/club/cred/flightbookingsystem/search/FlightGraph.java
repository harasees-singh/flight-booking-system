package club.cred.flightbookingsystem.search;

import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.repository.FlightRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-memory directed graph of the airline's flights.
 * <p>Node = city/airport code, edge = a direct {@link Flight}. The whole flight set
 * (2000–3000 rows) fits comfortably in memory, so the graph holds every flight keyed by
 * its source city. It is loaded once at startup and refreshed on a schedule; reads are
 * lock-free via an immutable adjacency map swapped atomically on each refresh.
 */
@Component
public class FlightGraph {

    private static final Logger log = LoggerFactory.getLogger(FlightGraph.class);

    private final FlightRepository flightRepository;

    /** Immutable adjacency list (source city -> outbound flights). Swapped atomically on refresh. */
    private volatile Map<String, List<Flight>> adjacency = Map.of();

    public FlightGraph(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    /** Rebuild the graph from the database. Runs at startup and periodically. */
    @Scheduled(fixedDelayString = "${flightbooking.graph.refresh-interval-ms:300000}",
            initialDelayString = "${flightbooking.graph.refresh-interval-ms:300000}")
    @Transactional(readOnly = true)
    public void refresh() {
        long startNanos = System.nanoTime();
        try {
            List<Flight> flights = flightRepository.findAllWithAircraft();
            Map<String, List<Flight>> next = new HashMap<>();
            for (Flight flight : flights) {
                next.computeIfAbsent(flight.getSource(), k -> new ArrayList<>()).add(flight);
            }
            this.adjacency = next;
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("Flight graph refreshed: {} cities, {} flights in {} ms",
                    next.size(), flights.size(), elapsedMs);
        } catch (RuntimeException ex) {
            // Keep serving the previous (immutable) snapshot rather than going dark on a transient
            // DB hiccup; the next scheduled run will retry.
            log.error("Flight graph refresh failed; retaining previous snapshot ({} cities)",
                    adjacency.size(), ex);
        }
    }

    /** Outbound flights from the given city (never null). */
    public List<Flight> outboundFrom(String city) {
        return adjacency.getOrDefault(city, List.of());
    }

    public int cityCount() {
        return adjacency.size();
    }
}

