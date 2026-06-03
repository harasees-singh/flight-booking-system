package club.cred.flightbookingsystem.booking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.repository.BookingRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BookingExpirySweeperTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final BookingService bookingService = mock(BookingService.class);
    private final BookingExpirySweeper sweeper =
            new BookingExpirySweeper(bookingRepository, bookingService, 600);

    @Test
    void expiresEachStalePendingBooking() {
        when(bookingRepository.findStaleBookingIds(eq(BookingState.PENDING_PAYMENT), any()))
                .thenReturn(List.of(10L, 11L, 12L));

        sweeper.sweepExpiredBookings();

        verify(bookingService).expireBooking(10L);
        verify(bookingService).expireBooking(11L);
        verify(bookingService).expireBooking(12L);
    }

    @Test
    void queriesWithCutoffOfNowMinusTtl() {
        when(bookingRepository.findStaleBookingIds(eq(BookingState.PENDING_PAYMENT), any()))
                .thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusSeconds(600);
        sweeper.sweepExpiredBookings();
        LocalDateTime after = LocalDateTime.now().minusSeconds(600);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bookingRepository).findStaleBookingIds(eq(BookingState.PENDING_PAYMENT), captor.capture());
        LocalDateTime cutoff = captor.getValue();
        // cutoff ~= now - 600s
        org.assertj.core.api.Assertions.assertThat(cutoff).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    @Test
    void doesNothingWhenNoStaleBookings() {
        when(bookingRepository.findStaleBookingIds(eq(BookingState.PENDING_PAYMENT), any()))
                .thenReturn(List.of());

        sweeper.sweepExpiredBookings();

        verify(bookingService, never()).expireBooking(any());
    }
}

