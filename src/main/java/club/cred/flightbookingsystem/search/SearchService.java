package club.cred.flightbookingsystem.search;

import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.dto.FlightLegDto;
import club.cred.flightbookingsystem.dto.JourneyDto;
import club.cred.flightbookingsystem.dto.SearchResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Search for journeys (direct + connecting, up to {@code maxLegs} legs) between two cities
 * for a given date and passenger count.
 *
 * <p>Uses <b>BFS</b> over the in-memory {@link FlightGraph}: level-order traversal surfaces
 * the fewest-leg journeys first and reliably enumerates every valid path within the depth
 * limit, avoiding the incorrect / sub-optimal path ordering DFS can produce. Seat availability
 * here is <i>approximate</i> (filters {@code seatsRemaining >= pax} from the last graph refresh);
 * the exact check is enforced atomically at booking time.
 */
@Service
public class SearchService {

    private final FlightGraph flightGraph;
    private final int maxLegs;
    private final long minLayoverMinutes;
    private final long maxLayoverMinutes;

    public SearchService(FlightGraph flightGraph,
                         @Value("${flightbooking.search.max-legs:3}") int maxLegs,
                         @Value("${flightbooking.search.min-layover-minutes:60}") long minLayoverMinutes,
                         @Value("${flightbooking.search.max-layover-minutes:720}") long maxLayoverMinutes) {
        this.flightGraph = flightGraph;
        this.maxLegs = maxLegs;
        this.minLayoverMinutes = minLayoverMinutes;
        this.maxLayoverMinutes = maxLayoverMinutes;
    }

    public SearchResponse search(String source, String destination, LocalDate date, int passengers) {
        List<JourneyDto> journeys = bfs(source, destination, date, passengers);
        journeys.sort(Comparator
                .comparingInt(JourneyDto::stops)
                .thenComparingInt(JourneyDto::totalDurationMin));
        return new SearchResponse(source, destination, date, passengers, journeys.size(), journeys);
    }

    private List<JourneyDto> bfs(String source, String destination, LocalDate date, int passengers) {
        List<JourneyDto> results = new ArrayList<>();
        if (source.equals(destination)) {
            return results;
        }

        Queue<PathState> queue = new ArrayDeque<>();
        Set<String> startVisited = new HashSet<>();
        startVisited.add(source);
        queue.add(new PathState(source, new ArrayList<>(), startVisited));

        while (!queue.isEmpty()) {
            PathState state = queue.poll();
            if (state.legs.size() >= maxLegs) {
                continue;
            }
            for (Flight flight : flightGraph.outboundFrom(state.city)) {
                if (!isConnectable(flight, state, date, passengers)) {
                    continue;
                }
                String nextCity = flight.getDestination();
                List<Flight> nextLegs = new ArrayList<>(state.legs);
                nextLegs.add(flight);

                if (nextCity.equals(destination)) {
                    results.add(toJourney(nextLegs));
                    continue; // do not expand past the destination
                }
                if (state.visited.contains(nextCity)) {
                    continue; // avoid loops within a single journey
                }
                Set<String> nextVisited = new HashSet<>(state.visited);
                nextVisited.add(nextCity);
                queue.add(new PathState(nextCity, nextLegs, nextVisited));
            }
        }
        return results;
    }

    private boolean isConnectable(Flight flight, PathState state, LocalDate date, int passengers) {
        if (flight.getSeatsRemaining() < passengers) {
            return false;
        }
        if (state.legs.isEmpty()) {
            // First leg must depart on the requested date.
            return flight.getDepartureTime().toLocalDate().equals(date);
        }
        // Connecting leg must depart after previous arrival + min layover, within max layover.
        Flight previous = state.legs.get(state.legs.size() - 1);
        long layoverMin = java.time.Duration
                .between(previous.getArrivalTime(), flight.getDepartureTime()).toMinutes();
        return layoverMin >= minLayoverMinutes && layoverMin <= maxLayoverMinutes;
    }

    private JourneyDto toJourney(List<Flight> legs) {
        List<FlightLegDto> legDtos = new ArrayList<>(legs.size());
        BigDecimal totalFare = BigDecimal.ZERO;
        int totalDuration = 0;
        for (Flight f : legs) {
            legDtos.add(new FlightLegDto(
                    f.getId(), f.getSource(), f.getDestination(),
                    f.getDepartureTime(), f.getArrivalTime(), f.getFlightDurationMin(),
                    f.getSeatsRemaining(), f.getAircraft().getModel(), f.getBaseFare()));
            totalFare = totalFare.add(f.getBaseFare());
            totalDuration += f.getFlightDurationMin();
        }
        int stops = legs.size() - 1;
        return new JourneyDto(legDtos, stops, totalDuration, totalFare);
    }

    /** Mutable BFS frontier node: current city, legs taken so far, cities already visited. */
    private record PathState(String city, List<Flight> legs, Set<String> visited) {
    }
}

