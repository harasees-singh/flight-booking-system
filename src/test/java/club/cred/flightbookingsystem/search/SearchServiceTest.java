package club.cred.flightbookingsystem.search;

import static club.cred.flightbookingsystem.search.FlightFixtures.flight;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.dto.JourneyDto;
import club.cred.flightbookingsystem.dto.SearchResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the BFS journey search. The graph is mocked so each test controls the edges. */
class SearchServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 10);
    private static final int MAX_LEGS = 3;
    private static final long MIN_LAYOVER = 60;
    private static final long MAX_LAYOVER = 720;

    private final FlightGraph graph = mock(FlightGraph.class);
    private final SearchService service = new SearchService(graph, MAX_LEGS, MIN_LAYOVER, MAX_LAYOVER);

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DATE, LocalTime.of(hour, minute));
    }

    @Test
    void findsDirectFlight() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "BLR", at(9, 0), 120, 100, 4000)));

        SearchResponse response = service.search("DEL", "BLR", DATE, 2);

        assertThat(response.journeyCount()).isEqualTo(1);
        JourneyDto journey = response.journeys().get(0);
        assertThat(journey.legs()).hasSize(1);
        assertThat(journey.stops()).isZero();
        assertThat(journey.totalDurationMin()).isEqualTo(120);
        assertThat(journey.totalFarePerPassenger()).isEqualByComparingTo("4000");
    }

    @Test
    void findsConnectingFlightWithValidLayover() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "CCU", at(9, 0), 120, 100, 3000)));   // arrives 11:00
        when(graph.outboundFrom("CCU")).thenReturn(List.of(
                flight("CCU", "BLR", at(13, 0), 90, 100, 2500)));    // 120 min layover

        SearchResponse response = service.search("DEL", "BLR", DATE, 2);

        assertThat(response.journeyCount()).isEqualTo(1);
        JourneyDto journey = response.journeys().get(0);
        assertThat(journey.legs()).hasSize(2);
        assertThat(journey.stops()).isEqualTo(1);
        assertThat(journey.totalDurationMin()).isEqualTo(210);
        assertThat(journey.totalFarePerPassenger()).isEqualByComparingTo("5500");
    }

    @Test
    void excludesConnectionWhenLayoverTooShort() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "CCU", at(9, 0), 120, 100, 3000)));   // arrives 11:00
        when(graph.outboundFrom("CCU")).thenReturn(List.of(
                flight("CCU", "BLR", at(11, 30), 90, 100, 2500)));   // only 30 min layover

        SearchResponse response = service.search("DEL", "BLR", DATE, 2);

        assertThat(response.journeyCount()).isZero();
    }

    @Test
    void excludesConnectionWhenLayoverTooLong() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "CCU", at(6, 0), 60, 100, 3000)));    // arrives 07:00
        when(graph.outboundFrom("CCU")).thenReturn(List.of(
                // 13h layover (> 720 min max)
                flight("CCU", "BLR", at(20, 0), 90, 100, 2500)));

        SearchResponse response = service.search("DEL", "BLR", DATE, 2);

        assertThat(response.journeyCount()).isZero();
    }

    @Test
    void excludesFlightsWithoutEnoughSeats() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "BLR", at(9, 0), 120, 3, 4000)));     // only 3 seats left

        SearchResponse response = service.search("DEL", "BLR", DATE, 5);

        assertThat(response.journeyCount()).isZero();
    }

    @Test
    void firstLegMustDepartOnRequestedDate() {
        Flight nextDay = new Flight(null, "DEL", "BLR",
                LocalDateTime.of(DATE.plusDays(1), LocalTime.of(9, 0)),
                LocalDateTime.of(DATE.plusDays(1), LocalTime.of(11, 0)),
                120, 180, 100, BigDecimal.valueOf(4000));
        // null aircraft is fine: this flight is filtered out before any aircraft access.
        lenient().when(graph.outboundFrom("DEL")).thenReturn(List.of(nextDay));

        SearchResponse response = service.search("DEL", "BLR", DATE, 2);

        assertThat(response.journeyCount()).isZero();
    }

    @Test
    void findsJourneyAtExactlyMaxLegs() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "BOM", at(6, 0), 60, 100, 1000)));    // arr 07:00
        when(graph.outboundFrom("BOM")).thenReturn(List.of(
                flight("BOM", "HYD", at(9, 0), 60, 100, 1000)));    // arr 10:00
        when(graph.outboundFrom("HYD")).thenReturn(List.of(
                flight("HYD", "BLR", at(12, 0), 60, 100, 1000)));   // 3rd leg -> destination

        SearchResponse response = service.search("DEL", "BLR", DATE, 1);

        assertThat(response.journeyCount()).isEqualTo(1);
        assertThat(response.journeys().get(0).legs()).hasSize(3);
        assertThat(response.journeys().get(0).stops()).isEqualTo(2);
    }

    @Test
    void excludesJourneyBeyondMaxLegs() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "BOM", at(6, 0), 60, 100, 1000)));
        when(graph.outboundFrom("BOM")).thenReturn(List.of(
                flight("BOM", "HYD", at(9, 0), 60, 100, 1000)));
        when(graph.outboundFrom("HYD")).thenReturn(List.of(
                flight("HYD", "PNQ", at(12, 0), 60, 100, 1000)));
        when(graph.outboundFrom("PNQ")).thenReturn(List.of(
                flight("PNQ", "BLR", at(15, 0), 60, 100, 1000)));   // 4th leg -> destination

        SearchResponse response = service.search("DEL", "BLR", DATE, 1);

        assertThat(response.journeyCount()).isZero();
    }

    @Test
    void sortsByFewestStopsThenDuration() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "BLR", at(8, 0), 200, 100, 9000),    // direct, long
                flight("DEL", "HYD", at(6, 0), 60, 100, 1000)));   // start of a connection
        when(graph.outboundFrom("HYD")).thenReturn(List.of(
                flight("HYD", "BLR", at(9, 0), 60, 100, 1000)));   // connecting leg

        SearchResponse response = service.search("DEL", "BLR", DATE, 1);

        assertThat(response.journeyCount()).isEqualTo(2);
        // Direct (0 stops) must come before the connecting journey (1 stop), even though longer.
        assertThat(response.journeys().get(0).stops()).isZero();
        assertThat(response.journeys().get(1).stops()).isEqualTo(1);
    }

    @Test
    void avoidsLoopsWithinAJourney() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "BOM", at(6, 0), 60, 100, 1000)));   // arr 07:00
        when(graph.outboundFrom("BOM")).thenReturn(List.of(
                flight("BOM", "DEL", at(9, 0), 60, 100, 1000)));   // loops back to DEL, no path to BLR

        SearchResponse response = service.search("DEL", "BLR", DATE, 1);

        assertThat(response.journeyCount()).isZero();
    }

    @Test
    void returnsEmptyWhenSourceEqualsDestination() {
        SearchResponse response = service.search("DEL", "DEL", DATE, 1);

        assertThat(response.journeyCount()).isZero();
    }

    @Test
    void returnsEmptyWhenNoRouteExists() {
        when(graph.outboundFrom("DEL")).thenReturn(List.of(
                flight("DEL", "BOM", at(9, 0), 120, 100, 4000)));  // only reaches BOM

        SearchResponse response = service.search("DEL", "BLR", DATE, 2);

        assertThat(response.journeyCount()).isZero();
    }
}

