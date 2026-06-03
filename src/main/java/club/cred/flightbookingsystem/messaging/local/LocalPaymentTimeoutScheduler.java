package club.cred.flightbookingsystem.messaging.local;

import club.cred.flightbookingsystem.booking.BookingService;
import club.cred.flightbookingsystem.messaging.PaymentTimeoutScheduler;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Fallback expiry scheduler used when Kafka is disabled: schedules the expiry in-process via the
 * {@link TaskScheduler}. {@link BookingService} is resolved lazily through an {@link ObjectProvider}
 * to break the construction cycle (BookingService -> scheduler -> BookingService).
 */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled", havingValue = "false")
public class LocalPaymentTimeoutScheduler implements PaymentTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalPaymentTimeoutScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ObjectProvider<BookingService> bookingService;

    public LocalPaymentTimeoutScheduler(TaskScheduler taskScheduler,
                                        ObjectProvider<BookingService> bookingService) {
        this.taskScheduler = taskScheduler;
        this.bookingService = bookingService;
    }

    @Override
    public void scheduleExpiry(Long bookingId, Instant expireAt) {
        log.info("Scheduling local expiry for booking {} at {}", bookingId, expireAt);
        taskScheduler.schedule(() -> bookingService.getObject().expireBooking(bookingId), expireAt);
    }
}

