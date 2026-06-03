package club.cred.flightbookingsystem.messaging;

import java.math.BigDecimal;

/** Refund request published to {@code payment.refund} for the (black-box) refund processor. */
public record RefundMessage(Long refundId, Long bookingId, BigDecimal amount) {
}

