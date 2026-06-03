package club.cred.flightbookingsystem.messaging;

/** Kafka topic names used across the booking lifecycle. */
public final class KafkaTopics {

    /** Inbound: payment system confirms/denies a pending booking. */
    public static final String PAYMENT_CALLBACK = "payment.callback";

    /** Per-booking delayed expiry of stale PENDING_PAYMENT bookings. */
    public static final String PAYMENT_TIMEOUT = "payment.timeout";

    /** Outbound: refund requests to the (black-box) refund processor. */
    public static final String PAYMENT_REFUND = "payment.refund";

    /** Audit / metrics stream of booking state changes. */
    public static final String BOOKING_EVENTS = "booking.events";

    private KafkaTopics() {
    }
}

