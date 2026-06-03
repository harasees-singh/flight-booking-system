package club.cred.flightbookingsystem.domain;

/** Lifecycle of a refund: INITIATED -> PROCESSING -> COMPLETED / FAILED. */
public enum RefundState {
    INITIATED,
    PROCESSING,
    COMPLETED,
    FAILED
}

