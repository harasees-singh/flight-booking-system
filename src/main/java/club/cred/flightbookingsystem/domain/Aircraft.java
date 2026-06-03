package club.cred.flightbookingsystem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An aircraft model. Single airline, multiple aircraft models.
 * Seats are a count only — no individual seat identity/characteristics.
 */
@Entity
@Table(name = "aircraft")
@Getter
public class Aircraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. A320, B737. */
    @Setter
    @Column(nullable = false)
    private String model;

    @Setter
    @Column(nullable = false)
    private int totalSeats;

    protected Aircraft() {
    }

    public Aircraft(String model, int totalSeats) {
        this.model = model;
        this.totalSeats = totalSeats;
    }
}

