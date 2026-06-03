package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.messaging.BookingEvent;
import club.cred.flightbookingsystem.messaging.BookingEventPublisher;
import club.cred.flightbookingsystem.messaging.KafkaTopics;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes booking state-change events to the {@code booking.events} topic. */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KafkaBookingEventPublisher implements BookingEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaBookingEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void bookingStateChanged(Booking booking) {
        BookingEvent event = new BookingEvent(booking.getId(), booking.getState(),
                booking.getTotalAmount(), Instant.now());
        kafkaTemplate.send(KafkaTopics.BOOKING_EVENTS, String.valueOf(booking.getId()), event);
    }
}

