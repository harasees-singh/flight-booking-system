package club.cred.flightbookingsystem.repository;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Compare-and-set the booking state in a single atomic statement. Returns 1 only if the
     * booking was in {@code expected} state and got moved to {@code newState}. Used to make
     * payment-confirm / failure / timeout transitions idempotent and race-safe (only the
     * winner unlocks seats).
     */
    @Modifying
    @Query("update Booking b set b.state = :newState, b.updatedAt = CURRENT_TIMESTAMP "
            + "where b.id = :id and b.state = :expected")
    int compareAndSetState(@Param("id") Long id,
                           @Param("expected") BookingState expected,
                           @Param("newState") BookingState newState);
}

