package club.cred.flightbookingsystem.repository;

import club.cred.flightbookingsystem.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {
}

