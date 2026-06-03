package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.domain.Refund;
import club.cred.flightbookingsystem.domain.RefundState;
import club.cred.flightbookingsystem.messaging.KafkaTopics;
import club.cred.flightbookingsystem.messaging.RefundMessage;
import club.cred.flightbookingsystem.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stand-in for the external (black-box) refund processor. Consumes {@code payment.refund} and
 * marks the refund COMPLETED. In production this would be an entirely separate system that calls
 * back with the outcome; here it lets us observe the full cancellation → refund flow end-to-end.
 */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RefundProcessorStub {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessorStub.class);

    private final RefundRepository refundRepository;

    public RefundProcessorStub(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REFUND,
            groupId = "${spring.kafka.consumer.group-id:flightbookingsystem}")
    @Transactional
    public void onRefundRequested(RefundMessage message) {
        log.info("payment.refund received (black-box stub) -> {}", message);
        Refund refund = refundRepository.findById(message.refundId()).orElse(null);
        if (refund == null) {
            log.warn("Refund {} not found", message.refundId());
            return;
        }
        // Simulate the processor moving the refund through to completion.
        refund.setState(RefundState.COMPLETED);
        refundRepository.save(refund);
        log.info("Refund {} for booking {} COMPLETED (amount {})",
                refund.getId(), refund.getBookingId(), refund.getAmount());
    }
}

