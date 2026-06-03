package club.cred.flightbookingsystem.booking;

/** Thrown when a referenced entity (flight, booking) does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}

