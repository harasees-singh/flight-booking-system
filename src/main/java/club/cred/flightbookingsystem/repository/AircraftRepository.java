package club.cred.flightbookingsystem.repository;

import club.cred.flightbookingsystem.domain.Aircraft;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {
}

