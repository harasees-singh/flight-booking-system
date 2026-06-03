package club.cred.flightbookingsystem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * A direct (single-leg) flight operated by the airline.
 * Node = city/airport code; an edge in the search graph.
 */
@Entity
@Table(name = "flight", indexes = {
        @Index(name = "idx_flight_source", columnList = "source"),
        @Index(name = "idx_flight_src_dst", columnList = "source,destination")
})
@Getter
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "aircraft_id", nullable = false)
    private Aircraft aircraft;

    /** Origin city / airport code. */
    @Column(nullable = false, length = 8)
    private String source;

    /** Destination city / airport code. */
    @Column(nullable = false, length = 8)
    private String destination;

    @Column(nullable = false)
    private LocalDateTime departureTime;

    @Column(nullable = false)
    private LocalDateTime arrivalTime;

    @Column(nullable = false)
    private int flightDurationMin;

    @Column(nullable = false)
    private int totalSeats;

    @Setter
    @Column(nullable = false)
    private int seatsRemaining;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal baseFare;

    /** Optimistic lock to prevent overselling seats. */
    @Version
    private Long version;

    protected Flight() {
    }

    public Flight(Aircraft aircraft, String source, String destination,
                  LocalDateTime departureTime, LocalDateTime arrivalTime,
                  int flightDurationMin, int totalSeats, int seatsRemaining, BigDecimal baseFare) {
        this.aircraft = aircraft;
        this.source = source;
        this.destination = destination;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.flightDurationMin = flightDurationMin;
        this.totalSeats = totalSeats;
        this.seatsRemaining = seatsRemaining;
        this.baseFare = baseFare;
    }
}

