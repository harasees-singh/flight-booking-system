package club.cred.flightbookingsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlightbookingsystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlightbookingsystemApplication.class, args);
    }

}
