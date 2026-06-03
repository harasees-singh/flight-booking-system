package club.cred.flightbookingsystem.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import club.cred.flightbookingsystem.domain.Refund;
import club.cred.flightbookingsystem.domain.RefundState;
import club.cred.flightbookingsystem.messaging.RefundMessage;
import club.cred.flightbookingsystem.repository.RefundRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefundProcessorStubTest {

    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final RefundProcessorStub stub = new RefundProcessorStub(refundRepository);

    @Test
    void marksRefundCompleted() {
        Refund refund = new Refund(5L, BigDecimal.valueOf(4800), RefundState.INITIATED);
        when(refundRepository.findById(99L)).thenReturn(Optional.of(refund));

        stub.onRefundRequested(new RefundMessage(99L, 5L, BigDecimal.valueOf(4800)));

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RefundState.COMPLETED);
    }

    @Test
    void ignoresUnknownRefund() {
        when(refundRepository.findById(404L)).thenReturn(Optional.empty());

        stub.onRefundRequested(new RefundMessage(404L, 5L, BigDecimal.valueOf(100)));

        verify(refundRepository, never()).save(any());
    }
}

