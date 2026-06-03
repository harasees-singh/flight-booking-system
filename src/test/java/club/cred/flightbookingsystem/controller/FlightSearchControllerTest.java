package club.cred.flightbookingsystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.dto.SearchResponse;
import club.cred.flightbookingsystem.search.SearchService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plain unit test: the controller delegates to {@link SearchService} and returns its result. */
class FlightSearchControllerTest {

    private final SearchService searchService = mock(SearchService.class);
    private final FlightSearchController controller = new FlightSearchController(searchService);

    @Test
    void delegatesToSearchServiceAndReturnsResult() {
        LocalDate date = LocalDate.of(2026, 6, 10);
        SearchResponse expected = new SearchResponse("DEL", "BLR", date, 2, 0, List.of());
        when(searchService.search("DEL", "BLR", date, 2)).thenReturn(expected);

        SearchResponse actual = controller.search("DEL", "BLR", date, 2);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void passesArgumentsThroughUnchanged() {
        LocalDate date = LocalDate.of(2026, 7, 1);
        SearchResponse expected = new SearchResponse("BOM", "MAA", date, 5, 0, List.of());
        when(searchService.search("BOM", "MAA", date, 5)).thenReturn(expected);

        SearchResponse actual = controller.search("BOM", "MAA", date, 5);

        assertThat(actual.source()).isEqualTo("BOM");
        assertThat(actual.destination()).isEqualTo("MAA");
        assertThat(actual.passengers()).isEqualTo(5);
    }

    @Test
    void doesNotCallServiceUntilInvoked() {
        // Constructing the controller must not trigger any search.
        verifyNoInteractions(searchService);
    }
}
