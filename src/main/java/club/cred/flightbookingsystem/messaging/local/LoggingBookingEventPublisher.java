package club.cred.flightbookingsystem.messaging.local;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.messaging.BookingEvent;
import club.cred.flightbookingsystem.messaging.BookingEventPublisher;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fallback publisher used when Kafka is disabled (e.g. the {@code local} profile): just logs. */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled", havingValue = "false")
public class LoggingBookingEventPublisher implements BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingBookingEventPublisher.class);

    @Override
    public void bookingStateChanged(Booking booking) {
        BookingEvent event = new BookingEvent(booking.getId(), booking.getState(),
                booking.getTotalAmount(), Instant.now());
        log.info("booking.events (local) -> {}", event);
    }
}

