package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.messaging.KafkaTopics;
import club.cred.flightbookingsystem.messaging.PaymentTimeoutMessage;
import club.cred.flightbookingsystem.messaging.PaymentTimeoutScheduler;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka implementation of expiry scheduling: publishes a per-booking delayed message to
 * {@code payment.timeout}. A consumer honours the delay and triggers the expiry. This replaces a
 * polling sweeper with a targeted, durable, per-booking trigger.
 */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KafkaPaymentTimeoutScheduler implements PaymentTimeoutScheduler {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPaymentTimeoutScheduler(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void scheduleExpiry(Long bookingId, Instant expireAt) {
        PaymentTimeoutMessage message = new PaymentTimeoutMessage(bookingId, expireAt.toEpochMilli());
        kafkaTemplate.send(KafkaTopics.PAYMENT_TIMEOUT, String.valueOf(bookingId), message);
    }
}

