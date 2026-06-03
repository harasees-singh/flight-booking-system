package club.cred.flightbookingsystem.booking;

import club.cred.flightbookingsystem.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Thin wrapper over the atomic seat block/release operations. */
@Service
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);

    private final FlightRepository flightRepository;

    public SeatService(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    /** @return true if {@code pax} seats were atomically blocked on the flight. */
    public boolean tryBlock(Long flightId, int pax) {
        boolean blocked = flightRepository.tryBlockSeats(flightId, pax) == 1;
        if (blocked) {
            log.debug("Blocked {} seat(s) on flight {}", pax, flightId);
        } else {
            log.warn("Seat block rejected: insufficient seats for {} pax on flight {}", pax, flightId);
        }
        return blocked;
    }

    /** Release {@code pax} seats back to the flight. */
    public void release(Long flightId, int pax) {
        int updated = flightRepository.releaseSeats(flightId, pax);
        if (updated == 1) {
            log.debug("Released {} seat(s) on flight {}", pax, flightId);
        } else {
            // Not fatal, but indicates the flight row was missing/changed — worth surfacing.
            log.warn("Seat release affected {} rows for {} pax on flight {} (expected 1)",
                    updated, pax, flightId);
        }
    }
}

