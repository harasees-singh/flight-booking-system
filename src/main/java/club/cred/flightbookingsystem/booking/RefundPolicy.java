package club.cred.flightbookingsystem.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Partial-refund policy. Flat percentage of the booking total is refunded; the remainder is the
 * cancellation fee. Configurable via {@code flightbooking.refund.percentage} (default 80%).
 */
@Component
public class RefundPolicy {

    private final BigDecimal refundFraction;

    public RefundPolicy(@Value("${flightbooking.refund.percentage:80}") int percentage) {
        this.refundFraction = BigDecimal.valueOf(percentage)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    /** @return the refundable amount for a cancelled booking of the given total. */
    public BigDecimal refundAmount(BigDecimal totalAmount) {
        return totalAmount.multiply(refundFraction).setScale(2, RoundingMode.HALF_UP);
    }
}

