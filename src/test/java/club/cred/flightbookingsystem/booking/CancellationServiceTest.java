package club.cred.flightbookingsystem.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.domain.Passenger;
import club.cred.flightbookingsystem.domain.Refund;
import club.cred.flightbookingsystem.domain.RefundState;
import club.cred.flightbookingsystem.dto.CancellationResponse;
import club.cred.flightbookingsystem.messaging.BookingEventPublisher;
import club.cred.flightbookingsystem.messaging.RefundEventPublisher;
import club.cred.flightbookingsystem.metrics.BookingMetrics;
import club.cred.flightbookingsystem.repository.BookingRepository;
import club.cred.flightbookingsystem.repository.RefundRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CancellationServiceTest {

    private static final Aircraft AIRCRAFT = new Aircraft("A320", 180);
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 10, 9, 0);

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final SeatService seatService = mock(SeatService.class);
    private final RefundPolicy refundPolicy = mock(RefundPolicy.class);
    private final RefundEventPublisher refundEventPublisher = mock(RefundEventPublisher.class);
    private final BookingEventPublisher bookingEventPublisher = mock(BookingEventPublisher.class);

    private final CancellationService service = new CancellationService(
            bookingRepository, refundRepository, seatService, new BookingStateMachine(),
            refundPolicy, refundEventPublisher, bookingEventPublisher,
            new BookingMetrics(new SimpleMeterRegistry()));

    private static Flight flight(long id, String src, String dst) {
        Flight f = new Flight(AIRCRAFT, src, dst, T0, T0.plusHours(2), 120, 180, 100,
                BigDecimal.valueOf(3000));
        ReflectionTestUtils.setField(f, "id", id);
        return f;
    }

    private static Booking booking(long id, BookingState state, List<Flight> legs, int paxCount) {
        Booking b = new Booking(state);
        ReflectionTestUtils.setField(b, "id", id);
        b.getFlights().addAll(legs);
        for (int i = 0; i < paxCount; i++) {
            b.addPassenger(new Passenger("P" + i, 30, BigDecimal.valueOf(1000)));
        }
        b.setTotalAmount(BigDecimal.valueOf(6000));
        return b;
    }

    @Test
    void cancelReleasesSeatsAndRaisesRefund() {
        Booking b = booking(5L, BookingState.SUCCESS,
                List.of(flight(1L, "DEL", "BOM"), flight(2L, "BOM", "BLR")), 3);
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(b));
        when(bookingRepository.compareAndSetState(5L, BookingState.SUCCESS, BookingState.CANCELLED))
                .thenReturn(1);
        when(refundPolicy.refundAmount(BigDecimal.valueOf(6000))).thenReturn(new BigDecimal("4800.00"));
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> {
            Refund r = i.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 99L);
            return r;
        });

        CancellationResponse response = service.cancel(5L);

        assertThat(b.getState()).isEqualTo(BookingState.CANCELLED);
        verify(seatService).release(1L, 3);
        verify(seatService).release(2L, 3);
        verify(refundEventPublisher).refundRequested(any(Refund.class));
        verify(bookingEventPublisher).bookingStateChanged(b);

        assertThat(response.state()).isEqualTo(BookingState.CANCELLED);
        assertThat(response.refund().refundId()).isEqualTo(99L);
        assertThat(response.refund().amount()).isEqualByComparingTo("4800.00");
        assertThat(response.refund().state()).isEqualTo(RefundState.INITIATED);
    }

    @Test
    void cancelRejectsNonSuccessBooking() {
        Booking b = booking(6L, BookingState.PENDING_PAYMENT, List.of(flight(1L, "DEL", "BLR")), 1);
        when(bookingRepository.findById(6L)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.cancel(6L))
                .isInstanceOf(InvalidBookingStateException.class);

        verify(seatService, never()).release(any(), anyInt());
        verifyNoInteractions(refundRepository, refundEventPublisher);
    }

    @Test
    void cancelFailsForUnknownBooking() {
        when(bookingRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cancelIsRaceSafeWhenStateChangesConcurrently() {
        Booking b = booking(7L, BookingState.SUCCESS, List.of(flight(1L, "DEL", "BLR")), 1);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(b));
        when(bookingRepository.compareAndSetState(7L, BookingState.SUCCESS, BookingState.CANCELLED))
                .thenReturn(0); // lost the race

        assertThatThrownBy(() -> service.cancel(7L))
                .isInstanceOf(InvalidBookingStateException.class);

        verify(seatService, never()).release(any(), anyInt());
        verifyNoInteractions(refundEventPublisher);
    }
}

