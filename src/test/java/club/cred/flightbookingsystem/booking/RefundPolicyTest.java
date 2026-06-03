package club.cred.flightbookingsystem.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RefundPolicyTest {

    @Test
    void refundsConfiguredPercentage() {
        RefundPolicy policy = new RefundPolicy(80);
        assertThat(policy.refundAmount(new BigDecimal("1000.00"))).isEqualByComparingTo("800.00");
        assertThat(policy.refundAmount(new BigDecimal("6248.00"))).isEqualByComparingTo("4998.40");
    }

    @Test
    void fullRefundWhenHundredPercent() {
        RefundPolicy policy = new RefundPolicy(100);
        assertThat(policy.refundAmount(new BigDecimal("5000.00"))).isEqualByComparingTo("5000.00");
    }

    @Test
    void noRefundWhenZeroPercent() {
        RefundPolicy policy = new RefundPolicy(0);
        assertThat(policy.refundAmount(new BigDecimal("5000.00"))).isEqualByComparingTo("0.00");
    }
}

