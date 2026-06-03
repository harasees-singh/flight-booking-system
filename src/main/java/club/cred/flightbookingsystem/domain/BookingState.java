package club.cred.flightbookingsystem.domain;

/**
 * Lifecycle of a booking.
 *
 * <pre>
 * INITIATED --> PENDING_PAYMENT --> SUCCESS
 *                     |               |
 *                     v               v
 *                  FAILURE        CANCELLED
 *               (unlock seats)  (unlock seats)
 * </pre>
 */
public enum BookingState {
    INITIATED,
    PENDING_PAYMENT,
    SUCCESS,
    FAILURE,
    CANCELLED
}

