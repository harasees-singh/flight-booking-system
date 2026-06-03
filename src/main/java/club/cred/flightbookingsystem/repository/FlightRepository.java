package club.cred.flightbookingsystem.repository;

import club.cred.flightbookingsystem.domain.Flight;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    /** All flights with their aircraft eagerly fetched — used to (re)build the search graph. */
    @Query("select f from Flight f join fetch f.aircraft")
    List<Flight> findAllWithAircraft();

    /**
     * Atomically block {@code pax} seats on a flight, but only if enough remain.
     * Returns the number of rows updated: 1 if seats were blocked, 0 if not enough seats
     * (prevents overselling without holding a pessimistic lock).
     */
    @Modifying
    @Query("update Flight f set f.seatsRemaining = f.seatsRemaining - :pax "
            + "where f.id = :id and f.seatsRemaining >= :pax")
    int tryBlockSeats(@Param("id") Long id, @Param("pax") int pax);

    /** Release {@code pax} previously-blocked seats back to the pool. */
    @Modifying
    @Query("update Flight f set f.seatsRemaining = f.seatsRemaining + :pax where f.id = :id")
    int releaseSeats(@Param("id") Long id, @Param("pax") int pax);
}

