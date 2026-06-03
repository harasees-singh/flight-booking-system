package club.cred.flightbookingsystem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * A passenger on a booking. Carries the per-passenger fare for the journey
 * (the {@code Map<Passenger, rate>} from the design is modelled as this column).
 */
@Entity
@Table(name = "passenger")
@Getter
public class Passenger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int age;

    /** Per-passenger fare for the whole journey (sum of leg base fares). */
    @Setter
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal bookingRate;

    @Setter
    @ManyToOne(optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    protected Passenger() {
    }

    public Passenger(String name, int age, BigDecimal bookingRate) {
        this.name = name;
        this.age = age;
        this.bookingRate = bookingRate;
    }
}

