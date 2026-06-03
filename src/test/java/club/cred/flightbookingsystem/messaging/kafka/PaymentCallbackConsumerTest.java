package club.cred.flightbookingsystem.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import club.cred.flightbookingsystem.booking.BookingService;
import club.cred.flightbookingsystem.messaging.PaymentCallbackMessage;
import org.junit.jupiter.api.Test;

class PaymentCallbackConsumerTest {

    private final BookingService bookingService = mock(BookingService.class);
    private final PaymentCallbackConsumer consumer = new PaymentCallbackConsumer(bookingService);

    @Test
    void delegatesSuccessfulCallbackToBookingService() {
        consumer.onPaymentCallback(new PaymentCallbackMessage(42L, true));

        verify(bookingService).confirmPayment(42L, true);
    }

    @Test
    void delegatesDenialToBookingService() {
        consumer.onPaymentCallback(new PaymentCallbackMessage(42L, false));

        verify(bookingService).confirmPayment(42L, false);
    }

    @Test
    void ignoresNullMessage() {
        consumer.onPaymentCallback(null);

        verifyNoInteractions(bookingService);
    }

    @Test
    void ignoresMessageWithNullBookingId() {
        consumer.onPaymentCallback(new PaymentCallbackMessage(null, true));

        verifyNoInteractions(bookingService);
    }

    @Test
    void rethrowsSoContainerCanRetry() {
        doThrow(new RuntimeException("db down"))
                .when(bookingService).confirmPayment(7L, true);

        assertThatThrownBy(() -> consumer.onPaymentCallback(new PaymentCallbackMessage(7L, true)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
    }
}

