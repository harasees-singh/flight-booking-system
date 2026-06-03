package club.cred.flightbookingsystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PassengerRequest(
        @NotBlank String name,
        @Min(0) @Max(120) int age) {
}

