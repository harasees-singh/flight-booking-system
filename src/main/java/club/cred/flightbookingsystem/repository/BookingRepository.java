package club.cred.flightbookingsystem.repository;

import club.cred.flightbookingsystem.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {
}

