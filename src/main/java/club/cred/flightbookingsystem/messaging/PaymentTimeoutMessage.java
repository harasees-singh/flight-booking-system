package club.cred.flightbookingsystem.messaging;

/** Per-booking delayed expiry instruction published to {@code payment.timeout}. */
public record PaymentTimeoutMessage(Long bookingId, long expireAtEpochMs) {
}

