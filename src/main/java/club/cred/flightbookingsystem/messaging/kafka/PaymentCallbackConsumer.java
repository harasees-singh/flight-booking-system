package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.booking.BookingService;
import club.cred.flightbookingsystem.messaging.KafkaTopics;
import club.cred.flightbookingsystem.messaging.PaymentCallbackMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes payment confirm/deny messages from the payment system and drives the booking state. */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PaymentCallbackConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackConsumer.class);

    private final BookingService bookingService;

    public PaymentCallbackConsumer(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_CALLBACK,
            groupId = "${spring.kafka.consumer.group-id:flightbookingsystem}")
    public void onPaymentCallback(PaymentCallbackMessage message) {
        if (message == null || message.bookingId() == null) {
            log.warn("Discarding malformed payment.callback message: {}", message);
            return;
        }
        log.info("payment.callback received for booking {} success={}",
                message.bookingId(), message.success());
        try {
            bookingService.confirmPayment(message.bookingId(), message.success());
        } catch (RuntimeException ex) {
            // Rethrow so the listener container's error handler can retry / route to DLT;
            // log here for immediate visibility with full context.
            log.error("Failed to process payment.callback for booking {} success={}",
                    message.bookingId(), message.success(), ex);
            throw ex;
        }
    }
}

