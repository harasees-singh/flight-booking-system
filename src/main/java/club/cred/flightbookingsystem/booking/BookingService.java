package club.cred.flightbookingsystem.booking;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the booking lifecycle and state machine.
 *
 * <pre>
 * INITIATED --> PENDING_PAYMENT --> SUCCESS
 *                     |               |
 *                     v               v
 *                  FAILURE        CANCELLED
 *               (unlock seats)  (unlock seats)
 * </pre>
 *
 * Booking is a two-interaction flow: a synchronous seat-block call ({@link #createBooking})
 * followed by an asynchronous payment confirmation ({@link #confirmPayment}) arriving over Kafka.
 * Stale pending bookings are expired via a per-booking delayed message ({@link #expireBooking}).
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final FlightRepository flightRepository;
    private final SeatService seatService;
    private final BookingEventPublisher eventPublisher;
    private final PaymentTimeoutScheduler timeoutScheduler;

    private final int maxLegs;
    private final long minLayoverMinutes;
    private final long maxLayoverMinutes;
    private final long paymentTtlSeconds;

    public BookingService(BookingRepository bookingRepository,
                          FlightRepository flightRepository,
                          SeatService seatService,
                          BookingEventPublisher eventPublisher,
                          PaymentTimeoutScheduler timeoutScheduler,
                          @Value("${flightbooking.search.max-legs:3}") int maxLegs,
                          @Value("${flightbooking.search.min-layover-minutes:60}") long minLayoverMinutes,
                          @Value("${flightbooking.search.max-layover-minutes:720}") long maxLayoverMinutes,
                          @Value("${flightbooking.booking.payment-ttl-seconds:600}") long paymentTtlSeconds) {
        this.bookingRepository = bookingRepository;
        this.flightRepository = flightRepository;
        this.seatService = seatService;
        this.eventPublisher = eventPublisher;
        this.timeoutScheduler = timeoutScheduler;
        this.maxLegs = maxLegs;
        this.minLayoverMinutes = minLayoverMinutes;
        this.maxLayoverMinutes = maxLayoverMinutes;
        this.paymentTtlSeconds = paymentTtlSeconds;
    }

    /**
     * Call 1 (synchronous): validate the journey, atomically block seats on every leg, and
     * create the booking in {@code PENDING_PAYMENT}. Then arm the expiry timer. The client now
     * proceeds to pay at the (black-box) payment system.
     */
    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request) {
        int pax = request.passengers().size();
        List<Flight> legs = loadOrderedLegs(request.flightIds());
        validateJourney(legs);

        List<Long> blocked = new ArrayList<>();
        for (Flight leg : legs) {
            if (seatService.tryBlock(leg.getId(), pax)) {
                blocked.add(leg.getId());
            } else {
                // Roll back any seats already blocked on earlier legs, then fail the request.
                blocked.forEach(id -> seatService.release(id, pax));
                throw new SeatUnavailableException(
                        "Only fewer than " + pax + " seats remain on flight " + leg.getId());
            }
        }

        BigDecimal perPassengerRate = legs.stream()
                .map(Flight::getBaseFare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = perPassengerRate.multiply(BigDecimal.valueOf(pax));

        Booking booking = new Booking(BookingState.PENDING_PAYMENT);
        booking.getFlights().addAll(legs);
        booking.setTotalAmount(totalAmount);
        for (PassengerRequest p : request.passengers()) {
            booking.addPassenger(new Passenger(p.name(), p.age(), perPassengerRate));
        }
        bookingRepository.save(booking);

        Instant expireAt = Instant.now().plus(Duration.ofSeconds(paymentTtlSeconds));
        timeoutScheduler.scheduleExpiry(booking.getId(), expireAt);
        eventPublisher.bookingStateChanged(booking);

        log.info("Booking {} created in PENDING_PAYMENT ({} pax, {} legs, amount {})",
                booking.getId(), pax, legs.size(), totalAmount);
        return BookingResponse.from(booking);
    }

    /**
     * Call 2 (asynchronous, from the {@code payment.callback} topic): confirm or deny payment.
     * The state transition is a single compare-and-set so it is idempotent and races safely with
     * the expiry path — only the winner unlocks seats.
     */
    @Transactional
    public void confirmPayment(Long bookingId, boolean success) {
        BookingState target = success ? BookingState.SUCCESS : BookingState.FAILURE;
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            log.warn("Payment callback for unknown booking {}", bookingId);
            return;
        }
        int updated = bookingRepository.compareAndSetState(
                bookingId, BookingState.PENDING_PAYMENT, target);
        if (updated == 0) {
            log.info("Payment callback for booking {} ignored (already {})",
                    bookingId, booking.getState());
            return;
        }
        if (!success) {
            releaseSeats(booking);
        }
        booking.setState(target);
        eventPublisher.bookingStateChanged(booking);
        log.info("Booking {} -> {}", bookingId, target);
    }

    /**
     * Expire a stale {@code PENDING_PAYMENT} booking: move to {@code FAILURE} and release seats.
     * Idempotent — a no-op if payment already confirmed or it was already expired.
     */
    @Transactional
    public void expireBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return;
        }
        int updated = bookingRepository.compareAndSetState(
                bookingId, BookingState.PENDING_PAYMENT, BookingState.FAILURE);
        if (updated == 0) {
            return; // already SUCCESS / FAILURE / CANCELLED
        }
        releaseSeats(booking);
        booking.setState(BookingState.FAILURE);
        eventPublisher.bookingStateChanged(booking);
        log.info("Booking {} expired -> FAILURE (seats released)", bookingId);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
        // Touch lazy collections within the transaction so the response can be built.
        booking.getFlights().size();
        booking.getPassengers().size();
        return BookingResponse.from(booking);
    }

    private void releaseSeats(Booking booking) {
        int pax = booking.getPassengers().size();
        for (Flight leg : booking.getFlights()) {
            seatService.release(leg.getId(), pax);
        }
    }

    private List<Flight> loadOrderedLegs(List<Long> flightIds) {
        Map<Long, Flight> byId = flightRepository.findAllById(flightIds).stream()
                .collect(Collectors.toMap(Flight::getId, Function.identity()));
        List<Flight> legs = new ArrayList<>(flightIds.size());
        for (Long id : flightIds) {
            Flight flight = byId.get(id);
            if (flight == null) {
                throw new ResourceNotFoundException("Flight " + id + " not found");
            }
            legs.add(flight);
        }
        return legs;
    }

    private void validateJourney(List<Flight> legs) {
        if (legs.size() > maxLegs) {
            throw new IllegalArgumentException("A journey may have at most " + maxLegs + " legs");
        }
        for (int i = 0; i < legs.size() - 1; i++) {
            Flight current = legs.get(i);
            Flight next = legs.get(i + 1);
            if (!current.getDestination().equals(next.getSource())) {
                throw new IllegalArgumentException("Legs do not connect: " + current.getId()
                        + " arrives " + current.getDestination()
                        + " but " + next.getId() + " departs " + next.getSource());
            }
            long layover = Duration.between(current.getArrivalTime(), next.getDepartureTime()).toMinutes();
            if (layover < minLayoverMinutes || layover > maxLayoverMinutes) {
                throw new IllegalArgumentException("Invalid layover (" + layover + " min) between legs "
                        + current.getId() + " and " + next.getId());
            }
        }
    }
}

