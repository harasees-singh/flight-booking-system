package club.cred.flightbookingsystem.booking;

/** Thrown when an operation is not allowed for the booking's current state (e.g. cancel a non-SUCCESS booking). */
public class InvalidBookingStateException extends RuntimeException {

    public InvalidBookingStateException(String message) {
        super(message);
    }
}

