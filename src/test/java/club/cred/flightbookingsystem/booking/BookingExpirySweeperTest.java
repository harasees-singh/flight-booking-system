package club.cred.flightbookingsystem.booking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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

    @Test
    void continuesSweepingWhenOneExpiryFails() {
        when(bookingRepository.findStaleBookingIds(eq(BookingState.PENDING_PAYMENT), any()))
                .thenReturn(List.of(10L, 11L, 12L));
        doThrow(new RuntimeException("boom")).when(bookingService).expireBooking(11L);
        doNothing().when(bookingService).expireBooking(10L);
        doNothing().when(bookingService).expireBooking(12L);

        // A single failing booking must not abort the rest of the sweep nor propagate.
        sweeper.sweepExpiredBookings();

        verify(bookingService).expireBooking(10L);
        verify(bookingService).expireBooking(11L);
        verify(bookingService).expireBooking(12L);
    }

    @Test
    void swallowsQueryFailureSoSchedulerKeepsRunning() {
        when(bookingRepository.findStaleBookingIds(eq(BookingState.PENDING_PAYMENT), any()))
                .thenThrow(new RuntimeException("db unavailable"));

        // Must not throw — the next scheduled run will retry.
        sweeper.sweepExpiredBookings();

        verify(bookingService, never()).expireBooking(any());
    }
}

