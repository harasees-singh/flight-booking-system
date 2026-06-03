package club.cred.flightbookingsystem.messaging;

import java.time.Instant;

/**
 * Schedules the expiry of a stale {@code PENDING_PAYMENT} booking. The Kafka implementation
 * publishes a per-booking delayed message to {@code payment.timeout}; a local fallback schedules
 * the expiry in-process. This replaces a polling sweeper with a targeted, per-booking trigger.
 */
public interface PaymentTimeoutScheduler {

    void scheduleExpiry(Long bookingId, Instant expireAt);
}

