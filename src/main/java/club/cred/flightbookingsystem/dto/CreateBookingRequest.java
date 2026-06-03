package club.cred.flightbookingsystem.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to create a booking: the ordered flight legs of the chosen journey and the
 * passenger list (1–10 passengers; at most 3 legs).
 */
public record CreateBookingRequest(
        @NotEmpty @Size(max = 3, message = "a journey may have at most 3 legs")
        List<Long> flightIds,

        @NotEmpty @Size(max = 10, message = "at most 10 passengers per booking")
        @Valid List<PassengerRequest> passengers) {
}

