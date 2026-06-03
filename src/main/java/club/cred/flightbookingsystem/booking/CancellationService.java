package club.cred.flightbookingsystem.booking;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.domain.Refund;
import club.cred.flightbookingsystem.domain.RefundState;
import club.cred.flightbookingsystem.dto.CancellationResponse;
import club.cred.flightbookingsystem.messaging.BookingEventPublisher;
import club.cred.flightbookingsystem.messaging.RefundEventPublisher;
import club.cred.flightbookingsystem.metrics.BookingMetrics;
import club.cred.flightbookingsystem.repository.BookingRepository;
import club.cred.flightbookingsystem.repository.RefundRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancellation flow:
 * <ol>
 *   <li>validate the booking is cancellable (state {@code SUCCESS});</li>
 *   <li>unblock the seats on every leg;</li>
 *   <li>move the booking to {@code CANCELLED};</li>
 *   <li>raise a (partial) refund and publish it to the black-box refund processor.</li>
 * </ol>
 */
@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private final BookingRepository bookingRepository;
    private final RefundRepository refundRepository;
    private final SeatService seatService;
    private final BookingStateMachine stateMachine;
    private final RefundPolicy refundPolicy;
    private final RefundEventPublisher refundEventPublisher;
    private final BookingEventPublisher bookingEventPublisher;
    private final BookingMetrics bookingMetrics;

    public CancellationService(BookingRepository bookingRepository,
                               RefundRepository refundRepository,
                               SeatService seatService,
                               BookingStateMachine stateMachine,
                               RefundPolicy refundPolicy,
                               RefundEventPublisher refundEventPublisher,
                               BookingEventPublisher bookingEventPublisher,
                               BookingMetrics bookingMetrics) {
        this.bookingRepository = bookingRepository;
        this.refundRepository = refundRepository;
        this.seatService = seatService;
        this.stateMachine = stateMachine;
        this.refundPolicy = refundPolicy;
        this.refundEventPublisher = refundEventPublisher;
        this.bookingEventPublisher = bookingEventPublisher;
        this.bookingMetrics = bookingMetrics;
    }

    @Transactional
    public CancellationResponse cancel(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));

        // 1. Validate: only a confirmed (SUCCESS) booking can be cancelled.
        if (booking.getState() != BookingState.SUCCESS) {
            throw new InvalidBookingStateException(
                    "Booking " + bookingId + " is " + booking.getState() + " and cannot be cancelled");
        }

        // 3. Atomic, race-safe transition SUCCESS -> CANCELLED (guards against double cancellation).
        stateMachine.validate(BookingState.SUCCESS, BookingState.CANCELLED);
        if (bookingRepository.compareAndSetState(bookingId, BookingState.SUCCESS, BookingState.CANCELLED) == 0) {
            throw new InvalidBookingStateException("Booking " + bookingId + " is no longer cancellable");
        }
        booking.setState(BookingState.CANCELLED);

        // 2. Unblock the seats on every leg.
        int pax = booking.getPassengers().size();
        for (Flight leg : booking.getFlights()) {
            seatService.release(leg.getId(), pax);
        }

        // 4. Raise a partial refund and hand it off to the black-box processor.
        BigDecimal amount = refundPolicy.refundAmount(booking.getTotalAmount());
        Refund refund = refundRepository.save(new Refund(bookingId, amount, RefundState.INITIATED));
        refundEventPublisher.refundRequested(refund);
        bookingEventPublisher.bookingStateChanged(booking);
        bookingMetrics.recordTransition(BookingState.CANCELLED);

        log.info("Booking {} CANCELLED; seats released; refund {} of {} initiated",
                bookingId, refund.getId(), amount);

        return new CancellationResponse(bookingId, booking.getState(),
                new CancellationResponse.RefundView(refund.getId(), refund.getAmount(), refund.getState()));
    }
}

