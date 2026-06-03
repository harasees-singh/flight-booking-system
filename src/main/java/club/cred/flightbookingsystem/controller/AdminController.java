package club.cred.flightbookingsystem.controller;

import club.cred.flightbookingsystem.domain.Aircraft;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.repository.AircraftRepository;
import club.cred.flightbookingsystem.repository.FlightRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin / seed lookup endpoints. */
@RestController
@RequestMapping("/api/v1")
public class AdminController {

    private final AircraftRepository aircraftRepository;
    private final FlightRepository flightRepository;

    public AdminController(AircraftRepository aircraftRepository, FlightRepository flightRepository) {
        this.aircraftRepository = aircraftRepository;
        this.flightRepository = flightRepository;
    }

    @GetMapping("/aircrafts")
    public List<Aircraft> aircrafts() {
        return aircraftRepository.findAll();
    }

    @GetMapping("/flights")
    public List<Flight> flights() {
        return flightRepository.findAllWithAircraft();
    }
}

