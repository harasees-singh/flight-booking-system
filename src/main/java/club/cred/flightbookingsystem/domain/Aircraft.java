package club.cred.flightbookingsystem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An aircraft model. Single airline, multiple aircraft models.
 * Seats are a count only — no individual seat identity/characteristics.
 */
@Entity
@Table(name = "aircraft")
public class Aircraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. A320, B737. */
    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private int totalSeats;

    protected Aircraft() {
    }

    public Aircraft(String model, int totalSeats) {
        this.model = model;
        this.totalSeats = totalSeats;
    }

    public Long getId() {
        return id;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public void setTotalSeats(int totalSeats) {
        this.totalSeats = totalSeats;
    }
}

