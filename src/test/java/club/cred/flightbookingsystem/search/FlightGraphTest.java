package club.cred.flightbookingsystem.search;

import static club.cred.flightbookingsystem.search.FlightFixtures.flight;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.repository.FlightRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the in-memory flight graph build/refresh. */
class FlightGraphTest {

    private static final LocalDateTime DEP = LocalDateTime.of(2026, 6, 10, 9, 0);

    private final FlightRepository repository = mock(FlightRepository.class);
    private final FlightGraph graph = new FlightGraph(repository);

    @Test
    void groupsFlightsBySourceCity() {
        Flight delToBlr = flight("DEL", "BLR", DEP, 120, 100, 4000);
        Flight delToBom = flight("DEL", "BOM", DEP, 90, 100, 3000);
        Flight bomToBlr = flight("BOM", "BLR", DEP, 80, 100, 2500);
        when(repository.findAllWithAircraft()).thenReturn(List.of(delToBlr, delToBom, bomToBlr));

        graph.refresh();

        assertThat(graph.cityCount()).isEqualTo(2);
        assertThat(graph.outboundFrom("DEL")).containsExactlyInAnyOrder(delToBlr, delToBom);
        assertThat(graph.outboundFrom("BOM")).containsExactly(bomToBlr);
    }

    @Test
    void returnsEmptyListForUnknownCity() {
        when(repository.findAllWithAircraft()).thenReturn(List.of());

        graph.refresh();

        assertThat(graph.outboundFrom("XXX")).isEmpty();
        assertThat(graph.cityCount()).isZero();
    }

    @Test
    void refreshReplacesPreviousGraph() {
        when(repository.findAllWithAircraft())
                .thenReturn(List.of(flight("DEL", "BLR", DEP, 120, 100, 4000)));
        graph.refresh();
        assertThat(graph.outboundFrom("DEL")).hasSize(1);

        when(repository.findAllWithAircraft())
                .thenReturn(List.of(flight("BOM", "BLR", DEP, 80, 100, 2500)));
        graph.refresh();

        assertThat(graph.outboundFrom("DEL")).isEmpty();
        assertThat(graph.outboundFrom("BOM")).hasSize(1);
    }
}

