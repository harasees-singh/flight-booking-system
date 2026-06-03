package club.cred.flightbookingsystem.booking;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Explicit booking state machine. Centralises the legal transitions so every state change goes
 * through one place and illegal transitions are rejected up-front.
 *
 * <pre>
 * INITIATED --> PENDING_PAYMENT --> SUCCESS ----> CANCELLED
 *                     |    |  \
 *                     |    |   '--> CANCELLED
 *                     |    '------> FAILURE
 *                     '-----------> (timeout) FAILURE
 * </pre>
 *
 * <ul>
 *   <li>{@code INITIATED}        -> PENDING_PAYMENT</li>
 *   <li>{@code PENDING_PAYMENT}  -> SUCCESS | FAILURE | CANCELLED</li>
 *   <li>{@code SUCCESS}          -> CANCELLED</li>
 *   <li>{@code FAILURE}          -> (terminal)</li>
 *   <li>{@code CANCELLED}        -> (terminal)</li>
 * </ul>
 */
@Component
public class BookingStateMachine {

    private static final Map<BookingState, Set<BookingState>> TRANSITIONS;

    static {
        Map<BookingState, Set<BookingState>> t = new EnumMap<>(BookingState.class);
        t.put(BookingState.INITIATED, EnumSet.of(BookingState.PENDING_PAYMENT));
        t.put(BookingState.PENDING_PAYMENT,
                EnumSet.of(BookingState.SUCCESS, BookingState.FAILURE, BookingState.CANCELLED));
        t.put(BookingState.SUCCESS, EnumSet.of(BookingState.CANCELLED));
        t.put(BookingState.FAILURE, EnumSet.noneOf(BookingState.class));
        t.put(BookingState.CANCELLED, EnumSet.noneOf(BookingState.class));
        TRANSITIONS = t;
    }

    /** @return the states reachable in one step from {@code state}. */
    public Set<BookingState> allowedFrom(BookingState state) {
        return TRANSITIONS.getOrDefault(state, EnumSet.noneOf(BookingState.class));
    }

    /** @return true if {@code from -> to} is a legal transition. */
    public boolean isLegal(BookingState from, BookingState to) {
        return allowedFrom(from).contains(to);
    }

    /** Throw {@link IllegalStateException} if {@code from -> to} is not a legal transition. */
    public void validate(BookingState from, BookingState to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateException(
                    "Illegal booking transition " + from + " -> " + to);
        }
    }

    /**
     * Validate and apply a transition to an in-memory booking (used before the entity is first
     * persisted, where the DB compare-and-set guard does not yet apply).
     */
    public void apply(Booking booking, BookingState to) {
        validate(booking.getState(), to);
        booking.setState(to);
    }
}

