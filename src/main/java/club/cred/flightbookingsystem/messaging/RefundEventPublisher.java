package club.cred.flightbookingsystem.messaging;

import club.cred.flightbookingsystem.domain.Refund;

/** Publishes refund requests (to {@code payment.refund}) for the black-box refund processor. */
public interface RefundEventPublisher {

    void refundRequested(Refund refund);
}

