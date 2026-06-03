package club.cred.flightbookingsystem.messaging;

/** Payload published by the payment system to {@code payment.callback}. */
public record PaymentCallbackMessage(Long bookingId, boolean success) {
}

