package club.cred.flightbookingsystem.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import org.junit.jupiter.api.Test;

class BookingStateMachineTest {

    private final BookingStateMachine stateMachine = new BookingStateMachine();

    @Test
    void allowsLegalTransitions() {
        assertThat(stateMachine.isLegal(BookingState.INITIATED, BookingState.PENDING_PAYMENT)).isTrue();
        assertThat(stateMachine.isLegal(BookingState.PENDING_PAYMENT, BookingState.SUCCESS)).isTrue();
        assertThat(stateMachine.isLegal(BookingState.PENDING_PAYMENT, BookingState.FAILURE)).isTrue();
        assertThat(stateMachine.isLegal(BookingState.PENDING_PAYMENT, BookingState.CANCELLED)).isTrue();
        assertThat(stateMachine.isLegal(BookingState.SUCCESS, BookingState.CANCELLED)).isTrue();
    }

    @Test
    void rejectsIllegalTransitions() {
        assertThat(stateMachine.isLegal(BookingState.INITIATED, BookingState.SUCCESS)).isFalse();
        assertThat(stateMachine.isLegal(BookingState.SUCCESS, BookingState.FAILURE)).isFalse();
        assertThat(stateMachine.isLegal(BookingState.SUCCESS, BookingState.PENDING_PAYMENT)).isFalse();
        assertThat(stateMachine.isLegal(BookingState.PENDING_PAYMENT, BookingState.INITIATED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThat(stateMachine.allowedFrom(BookingState.FAILURE)).isEmpty();
        assertThat(stateMachine.allowedFrom(BookingState.CANCELLED)).isEmpty();
    }

    @Test
    void validateThrowsOnIllegalTransition() {
        assertThatThrownBy(() ->
                stateMachine.validate(BookingState.SUCCESS, BookingState.FAILURE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUCCESS -> FAILURE");
    }

    @Test
    void applyValidatesAndUpdatesBookingState() {
        Booking booking = new Booking(BookingState.INITIATED);

        stateMachine.apply(booking, BookingState.PENDING_PAYMENT);

        assertThat(booking.getState()).isEqualTo(BookingState.PENDING_PAYMENT);
    }

    @Test
    void applyRejectsIllegalTransitionAndLeavesStateUnchanged() {
        Booking booking = new Booking(BookingState.INITIATED);

        assertThatThrownBy(() -> stateMachine.apply(booking, BookingState.SUCCESS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(booking.getState()).isEqualTo(BookingState.INITIATED);
    }
}

