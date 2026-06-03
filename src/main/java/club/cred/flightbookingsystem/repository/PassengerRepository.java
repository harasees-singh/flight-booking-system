package club.cred.flightbookingsystem.repository;

import club.cred.flightbookingsystem.domain.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {
}

