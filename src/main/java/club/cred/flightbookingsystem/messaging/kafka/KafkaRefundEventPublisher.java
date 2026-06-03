package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.domain.Refund;
import club.cred.flightbookingsystem.messaging.KafkaTopics;
import club.cred.flightbookingsystem.messaging.RefundEventPublisher;
import club.cred.flightbookingsystem.messaging.RefundMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes refund requests to the {@code payment.refund} topic. */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KafkaRefundEventPublisher implements RefundEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaRefundEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void refundRequested(Refund refund) {
        RefundMessage message = new RefundMessage(refund.getId(), refund.getBookingId(), refund.getAmount());
        kafkaTemplate.send(KafkaTopics.PAYMENT_REFUND, String.valueOf(refund.getBookingId()), message);
    }
}

