package club.cred.flightbookingsystem.messaging;

import club.cred.flightbookingsystem.domain.BookingState;
import java.math.BigDecimal;
import java.time.Instant;

/** Audit event emitted to {@code booking.events} whenever a booking changes state. */
public record BookingEvent(Long bookingId, BookingState state, BigDecimal totalAmount, Instant at) {
}

