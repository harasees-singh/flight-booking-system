package club.cred.flightbookingsystem.dto;

import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.RefundState;
import java.math.BigDecimal;

/** Result of cancelling a booking, including the raised (partial) refund. */
public record CancellationResponse(
        Long bookingId,
        BookingState state,
        RefundView refund) {

    public record RefundView(Long refundId, BigDecimal amount, RefundState state) {
    }
}

