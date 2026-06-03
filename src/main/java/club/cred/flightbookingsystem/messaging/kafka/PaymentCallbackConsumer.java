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
        log.info("payment.callback received for booking {} success={}",
                message.bookingId(), message.success());
        bookingService.confirmPayment(message.bookingId(), message.success());
    }
}

