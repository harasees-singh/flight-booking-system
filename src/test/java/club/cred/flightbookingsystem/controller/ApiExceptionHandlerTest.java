package club.cred.flightbookingsystem.controller;

import static org.assertj.core.api.Assertions.assertThat;

import club.cred.flightbookingsystem.booking.InvalidBookingStateException;
import club.cred.flightbookingsystem.booking.ResourceNotFoundException;
import club.cred.flightbookingsystem.booking.SeatUnavailableException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void badRequestMapsTo400WithMessage() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleBadRequest(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", 400);
        assertThat(response.getBody()).containsEntry("message", "bad input");
        assertThat(response.getBody()).containsKey("timestamp");
    }

    @Test
    void notFoundMapsTo404() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleNotFound(new ResourceNotFoundException("Booking 1 not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "Booking 1 not found");
    }

    @Test
    void seatUnavailableMapsTo409Conflict() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleSeatsUnavailable(new SeatUnavailableException("no seats"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "no seats");
    }

    @Test
    void invalidStateMapsTo409Conflict() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleInvalidState(new InvalidBookingStateException("not cancellable"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "not cancellable");
    }

    @Test
    void nullMessageBecomesEmptyString() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleBadRequest(new IllegalArgumentException((String) null));

        assertThat(response.getBody()).containsEntry("message", "");
    }

    @Test
    void unexpectedExceptionMapsTo500WithSanitizedBodyAndErrorId() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUnexpected(new RuntimeException("DB connection pool exhausted at 0xCAFEBABE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("status", 500);
        // Must NOT leak the internal exception message to the client.
        assertThat(response.getBody().get("message").toString()).doesNotContain("0xCAFEBABE");
        assertThat(response.getBody()).containsKey("errorId");
        assertThat(response.getBody().get("errorId").toString()).isNotBlank();
    }
}

