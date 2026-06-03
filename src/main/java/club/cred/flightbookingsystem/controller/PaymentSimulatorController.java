package club.cred.flightbookingsystem.controller;

import club.cred.flightbookingsystem.messaging.KafkaTopics;
import club.cred.flightbookingsystem.messaging.PaymentCallbackMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only stand-in for the black-box payment system. Publishes a {@link PaymentCallbackMessage}
 * to {@code payment.callback} so the booking flow can be exercised end-to-end without a real
 * payment provider. Only active when Kafka is enabled.
 */
@RestController
@RequestMapping("/api/v1/_sim")
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PaymentSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulatorController.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentSimulatorController(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publish a payment confirm/deny for a booking, mimicking the payment system's callback. */
    @PostMapping("/payment-callback")
    public ResponseEntity<String> publishPaymentCallback(
            @RequestParam Long bookingId,
            @RequestParam(defaultValue = "true") boolean success) {
        PaymentCallbackMessage message = new PaymentCallbackMessage(bookingId, success);
        kafkaTemplate.send(KafkaTopics.PAYMENT_CALLBACK, String.valueOf(bookingId), message);
        log.info("Simulated payment.callback published -> {}", message);
        return ResponseEntity.accepted()
                .body("Published payment.callback for booking " + bookingId + " success=" + success);
    }
}

