package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.booking.BookingService;
import club.cred.flightbookingsystem.messaging.KafkaTopics;
import club.cred.flightbookingsystem.messaging.PaymentTimeoutMessage;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Consumes per-booking {@code payment.timeout} messages and honours the requested delay: it
 * schedules {@link BookingService#expireBooking} to run at {@code expireAt} (or immediately if that
 * time has already passed). The expiry itself is idempotent, so duplicate deliveries are safe.
 */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PaymentTimeoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutConsumer.class);

    private final BookingService bookingService;
    private final TaskScheduler taskScheduler;

    public PaymentTimeoutConsumer(BookingService bookingService, TaskScheduler taskScheduler) {
        this.bookingService = bookingService;
        this.taskScheduler = taskScheduler;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_TIMEOUT,
            groupId = "${spring.kafka.consumer.group-id:flightbookingsystem}")
    public void onTimeout(PaymentTimeoutMessage message) {
        Instant expireAt = Instant.ofEpochMilli(message.expireAtEpochMs());
        log.info("payment.timeout received for booking {}, expiry at {}", message.bookingId(), expireAt);
        taskScheduler.schedule(() -> bookingService.expireBooking(message.bookingId()), expireAt);
    }
}

