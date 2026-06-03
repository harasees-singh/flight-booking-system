package club.cred.flightbookingsystem.booking;

import club.cred.flightbookingsystem.repository.FlightRepository;
import org.springframework.stereotype.Service;

/** Thin wrapper over the atomic seat block/release operations. */
@Service
public class SeatService {

    private final FlightRepository flightRepository;

    public SeatService(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    /** @return true if {@code pax} seats were atomically blocked on the flight. */
    public boolean tryBlock(Long flightId, int pax) {
        return flightRepository.tryBlockSeats(flightId, pax) == 1;
    }

    /** Release {@code pax} seats back to the flight. */
    public void release(Long flightId, int pax) {
        flightRepository.releaseSeats(flightId, pax);
    }
}

