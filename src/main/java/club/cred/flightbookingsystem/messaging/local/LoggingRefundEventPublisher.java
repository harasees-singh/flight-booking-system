package club.cred.flightbookingsystem.messaging.local;

import club.cred.flightbookingsystem.domain.Refund;
import club.cred.flightbookingsystem.messaging.RefundEventPublisher;
import club.cred.flightbookingsystem.messaging.RefundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fallback refund publisher used when Kafka is disabled (e.g. the {@code local} profile): logs. */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled", havingValue = "false")
public class LoggingRefundEventPublisher implements RefundEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingRefundEventPublisher.class);

    @Override
    public void refundRequested(Refund refund) {
        RefundMessage message = new RefundMessage(refund.getId(), refund.getBookingId(), refund.getAmount());
        log.info("payment.refund (local) -> {}", message);
    }
}

