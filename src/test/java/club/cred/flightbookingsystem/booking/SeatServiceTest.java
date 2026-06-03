package club.cred.flightbookingsystem.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.repository.FlightRepository;
import org.junit.jupiter.api.Test;

class SeatServiceTest {

    private final FlightRepository flightRepository = mock(FlightRepository.class);
    private final SeatService seatService = new SeatService(flightRepository);

    @Test
    void tryBlockReturnsTrueWhenOneRowUpdated() {
        when(flightRepository.tryBlockSeats(1L, 2)).thenReturn(1);

        assertThat(seatService.tryBlock(1L, 2)).isTrue();
        verify(flightRepository).tryBlockSeats(1L, 2);
    }

    @Test
    void tryBlockReturnsFalseWhenNoRowUpdated() {
        when(flightRepository.tryBlockSeats(1L, 5)).thenReturn(0);

        assertThat(seatService.tryBlock(1L, 5)).isFalse();
    }

    @Test
    void releaseDelegatesToRepository() {
        when(flightRepository.releaseSeats(3L, 4)).thenReturn(1);

        seatService.release(3L, 4);

        verify(flightRepository).releaseSeats(3L, 4);
    }

    @Test
    void releaseToleratesUnexpectedRowCount() {
        // A missing/changed flight row (0 rows) must not throw — it is logged and swallowed.
        when(flightRepository.releaseSeats(9L, 1)).thenReturn(0);

        seatService.release(9L, 1);

        verify(flightRepository).releaseSeats(9L, 1);
    }
}

