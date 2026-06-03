package club.cred.flightbookingsystem.booking;

import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.repository.BookingRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled sweeper that expires stale {@code PENDING_PAYMENT} bookings: any booking that has been
 * awaiting payment for longer than the TTL is moved to {@code FAILURE} and its seats released.
 *
 * <p>Chosen over an in-memory per-booking timer because it is <b>durable across restarts</b> — it
 * re-derives what needs expiring from the database on every run, so nothing is lost if the app
 * bounces. The actual transition is delegated to {@link BookingService#expireBooking}, which is
 * idempotent and race-safe (a booking confirmed in the meantime is skipped).
 */
@Component
public class BookingExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(BookingExpirySweeper.class);

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final long paymentTtlSeconds;

    public BookingExpirySweeper(BookingRepository bookingRepository,
                                BookingService bookingService,
                                @Value("${flightbooking.booking.payment-ttl-seconds:600}") long paymentTtlSeconds) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.paymentTtlSeconds = paymentTtlSeconds;
    }

    @Scheduled(fixedDelayString = "${flightbooking.booking.expiry-sweep-ms:300000}",
            initialDelayString = "${flightbooking.booking.expiry-sweep-ms:300000}")
    public void sweepExpiredBookings() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(paymentTtlSeconds);
        List<Long> staleIds = bookingRepository.findStaleBookingIds(BookingState.PENDING_PAYMENT, cutoff);
        if (staleIds.isEmpty()) {
            return;
        }
        log.info("Expiry sweeper: {} stale PENDING_PAYMENT booking(s) older than {} -> expiring",
                staleIds.size(), cutoff);
        for (Long bookingId : staleIds) {
            bookingService.expireBooking(bookingId);
        }
    }
}

