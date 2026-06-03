package club.cred.flightbookingsystem.booking;

/** Thrown when seats cannot be blocked on one of the legs (sold out for the requested pax). */
public class SeatUnavailableException extends RuntimeException {

    public SeatUnavailableException(String message) {
        super(message);
    }
}

