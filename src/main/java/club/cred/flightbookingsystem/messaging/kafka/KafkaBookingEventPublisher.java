package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.messaging.BookingEvent;
import club.cred.flightbookingsystem.messaging.BookingEventPublisher;
import club.cred.flightbookingsystem.messaging.KafkaTopics;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes booking state-change events to the {@code booking.events} topic. */
@Component
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KafkaBookingEventPublisher implements BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaBookingEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaBookingEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void bookingStateChanged(Booking booking) {
        BookingEvent event = new BookingEvent(booking.getId(), booking.getState(),
                booking.getTotalAmount(), Instant.now());
        String key = String.valueOf(booking.getId());
        kafkaTemplate.send(KafkaTopics.BOOKING_EVENTS, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Best-effort event stream: log loudly but do not fail the booking transaction.
                        log.error("Failed to publish booking.events for booking {} (state {})",
                                booking.getId(), booking.getState(), ex);
                    } else if (log.isDebugEnabled()) {
                        log.debug("Published booking.events for booking {} (state {}) to {}-{}@{}",
                                booking.getId(), booking.getState(),
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}

