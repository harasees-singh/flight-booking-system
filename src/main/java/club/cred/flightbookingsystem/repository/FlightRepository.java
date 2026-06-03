package club.cred.flightbookingsystem.repository;

import club.cred.flightbookingsystem.domain.Flight;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    /** All flights with their aircraft eagerly fetched — used to (re)build the search graph. */
    @Query("select f from Flight f join fetch f.aircraft")
    List<Flight> findAllWithAircraft();
}

