package club.cred.flightbookingsystem.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.domain.Passenger;
import club.cred.flightbookingsystem.dto.BookingResponse;
import club.cred.flightbookingsystem.dto.CreateBookingRequest;
import club.cred.flightbookingsystem.dto.PassengerRequest;
import club.cred.flightbookingsystem.messaging.BookingEventPublisher;
import club.cred.flightbookingsystem.messaging.PaymentTimeoutScheduler;
import club.cred.flightbookingsystem.repository.BookingRepository;
import club.cred.flightbookingsystem.repository.FlightRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class BookingServiceTest {

    private static final Aircraft AIRCRAFT = new Aircraft("A320", 180);
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 10, 9, 0);

    private final BookingRepository bookingRepository = org.mockito.Mockito.mock(BookingRepository.class);
    private final FlightRepository flightRepository = org.mockito.Mockito.mock(FlightRepository.class);
    private final SeatService seatService = org.mockito.Mockito.mock(SeatService.class);
    private final BookingEventPublisher eventPublisher = org.mockito.Mockito.mock(BookingEventPublisher.class);
    private final PaymentTimeoutScheduler timeoutScheduler = org.mockito.Mockito.mock(PaymentTimeoutScheduler.class);

    private final BookingService service = new BookingService(
            bookingRepository, flightRepository, seatService, new BookingStateMachine(),
            eventPublisher, timeoutScheduler,
            3, 60, 720, 600);

    private static Flight flight(long id, String src, String dst,
                                 LocalDateTime dep, int durationMin, double fare) {
        Flight f = new Flight(AIRCRAFT, src, dst, dep, dep.plusMinutes(durationMin),
                durationMin, AIRCRAFT.getTotalSeats(), 100, BigDecimal.valueOf(fare));
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
        return b;
    }

    // ---- createBooking -----------------------------------------------------

    @Test
    void createBookingBlocksSeatsAndPersistsPendingPayment() {
        Flight leg1 = flight(1L, "DEL", "BOM", T0, 120, 3000);          // arr 11:00
        Flight leg2 = flight(2L, "BOM", "BLR", T0.plusHours(4), 120, 2500); // dep 13:00, layover 120
        when(flightRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(leg1, leg2));
        when(seatService.tryBlock(any(), anyInt())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> {
            Booking saved = i.getArgument(0);
            long pid = 1;
            for (Passenger p : saved.getPassengers()) {
                ReflectionTestUtils.setField(p, "id", pid++);
            }
            return saved;
        });

        CreateBookingRequest request = new CreateBookingRequest(List.of(1L, 2L),
                List.of(new PassengerRequest("Alice", 30), new PassengerRequest("Bob", 28)));

        BookingResponse response = service.createBooking(request);

        assertThat(response.state()).isEqualTo(BookingState.PENDING_PAYMENT);
        assertThat(response.flightIds()).containsExactly(1L, 2L);
        // Per-passenger fares are a Map<passengerId, Money>; each passenger pays the same sum-of-legs fare.
        assertThat(response.passengerRates()).hasSize(2).containsKeys(1L, 2L);
        assertThat(response.passengerRates().values())
                .allSatisfy(rate -> assertThat(rate).isEqualByComparingTo("5500")); // 3000 + 2500
        assertThat(response.totalAmount()).isEqualByComparingTo("11000");      // x2 pax
        verify(seatService).tryBlock(1L, 2);
        verify(seatService).tryBlock(2L, 2);
        verify(timeoutScheduler).scheduleExpiry(any(), any(Instant.class));
        verify(eventPublisher).bookingStateChanged(any(Booking.class));
    }

    @Test
    void createBookingRollsBackSeatsWhenALegIsSoldOut() {
        Flight leg1 = flight(1L, "DEL", "BOM", T0, 120, 3000);
        Flight leg2 = flight(2L, "BOM", "BLR", T0.plusHours(4), 120, 2500);
        when(flightRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(leg1, leg2));
        when(seatService.tryBlock(eq(1L), anyInt())).thenReturn(true);
        when(seatService.tryBlock(eq(2L), anyInt())).thenReturn(false); // sold out

        CreateBookingRequest request = new CreateBookingRequest(List.of(1L, 2L),
                List.of(new PassengerRequest("Alice", 30)));

        assertThatThrownBy(() -> service.createBooking(request))
                .isInstanceOf(SeatUnavailableException.class);

        verify(seatService).release(1L, 1);            // first leg rolled back
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(timeoutScheduler);
    }

    @Test
    void createBookingRejectsNonConnectingLegs() {
        Flight leg1 = flight(1L, "DEL", "BOM", T0, 120, 3000);          // arrives BOM
        Flight leg2 = flight(2L, "HYD", "BLR", T0.plusHours(4), 120, 2500); // departs HYD
        when(flightRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(leg1, leg2));

        CreateBookingRequest request = new CreateBookingRequest(List.of(1L, 2L),
                List.of(new PassengerRequest("Alice", 30)));

        assertThatThrownBy(() -> service.createBooking(request))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(seatService);
    }

    @Test
    void createBookingFailsWhenFlightMissing() {
        Flight leg1 = flight(1L, "DEL", "BOM", T0, 120, 3000);
        when(flightRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(leg1)); // 2L missing

        CreateBookingRequest request = new CreateBookingRequest(List.of(1L, 2L),
                List.of(new PassengerRequest("Alice", 30)));

        assertThatThrownBy(() -> service.createBooking(request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- confirmPayment ----------------------------------------------------

    @Test
    void confirmPaymentSuccessMovesToSuccessWithoutReleasingSeats() {
        Booking b = booking(5L, BookingState.PENDING_PAYMENT,
                List.of(flight(1L, "DEL", "BLR", T0, 120, 4000)), 2);
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(b));
        when(bookingRepository.compareAndSetState(5L, BookingState.PENDING_PAYMENT, BookingState.SUCCESS))
                .thenReturn(1);

        service.confirmPayment(5L, true);

        assertThat(b.getState()).isEqualTo(BookingState.SUCCESS);
        verify(seatService, never()).release(any(), anyInt());
        verify(eventPublisher).bookingStateChanged(b);
    }

    @Test
    void confirmPaymentFailureReleasesSeats() {
        Booking b = booking(6L, BookingState.PENDING_PAYMENT,
                List.of(flight(1L, "DEL", "BOM", T0, 60, 2000),
                        flight(2L, "BOM", "BLR", T0.plusHours(3), 60, 2000)), 3);
        when(bookingRepository.findById(6L)).thenReturn(Optional.of(b));
        when(bookingRepository.compareAndSetState(6L, BookingState.PENDING_PAYMENT, BookingState.FAILURE))
                .thenReturn(1);

        service.confirmPayment(6L, false);

        assertThat(b.getState()).isEqualTo(BookingState.FAILURE);
        verify(seatService).release(1L, 3);
        verify(seatService).release(2L, 3);
        verify(eventPublisher).bookingStateChanged(b);
    }

    @Test
    void confirmPaymentIsIdempotentWhenAlreadyProcessed() {
        Booking b = booking(7L, BookingState.SUCCESS,
                List.of(flight(1L, "DEL", "BLR", T0, 120, 4000)), 1);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(b));
        when(bookingRepository.compareAndSetState(eq(7L), any(), any())).thenReturn(0);

        service.confirmPayment(7L, true);

        verify(seatService, never()).release(any(), anyInt());
        verify(eventPublisher, never()).bookingStateChanged(any());
    }

    @Test
    void confirmPaymentForUnknownBookingIsNoOp() {
        when(bookingRepository.findById(99L)).thenReturn(Optional.empty());

        service.confirmPayment(99L, true);

        verify(bookingRepository, never()).compareAndSetState(any(), any(), any());
        verifyNoInteractions(seatService);
    }

    // ---- expireBooking -----------------------------------------------------

    @Test
    void expireBookingFailsPendingAndReleasesSeats() {
        Booking b = booking(8L, BookingState.PENDING_PAYMENT,
                List.of(flight(1L, "DEL", "BLR", T0, 120, 4000)), 2);
        when(bookingRepository.findById(8L)).thenReturn(Optional.of(b));
        when(bookingRepository.compareAndSetState(8L, BookingState.PENDING_PAYMENT, BookingState.FAILURE))
                .thenReturn(1);

        service.expireBooking(8L);

        assertThat(b.getState()).isEqualTo(BookingState.FAILURE);
        verify(seatService).release(1L, 2);
        verify(eventPublisher).bookingStateChanged(b);
    }

    @Test
    void expireBookingIsNoOpWhenAlreadyConfirmed() {
        Booking b = booking(9L, BookingState.SUCCESS,
                List.of(flight(1L, "DEL", "BLR", T0, 120, 4000)), 1);
        when(bookingRepository.findById(9L)).thenReturn(Optional.of(b));
        when(bookingRepository.compareAndSetState(9L, BookingState.PENDING_PAYMENT, BookingState.FAILURE))
                .thenReturn(0);

        service.expireBooking(9L);

        verify(seatService, never()).release(any(), anyInt());
        verify(eventPublisher, never()).bookingStateChanged(any());
    }

    @Test
    void schedulesExpiryAtConfiguredTtl() {
        Flight leg = flight(1L, "DEL", "BLR", T0, 120, 4000);
        when(flightRepository.findAllById(List.of(1L))).thenReturn(List.of(leg));
        when(seatService.tryBlock(any(), anyInt())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        Instant before = Instant.now();
        service.createBooking(new CreateBookingRequest(List.of(1L),
                List.of(new PassengerRequest("Alice", 30))));

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(timeoutScheduler).scheduleExpiry(any(), captor.capture());
        Instant expireAt = captor.getValue();
        // TTL is 600s; allow a small execution window.
        assertThat(Duration.between(before, expireAt).getSeconds()).isBetween(595L, 605L);
    }
}

