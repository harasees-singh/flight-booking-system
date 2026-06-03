package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer error policy: on a processing failure, retry a few times with a fixed back-off and then
 * route the offending record to a per-topic <b>dead-letter topic</b> ({@code <topic>.DLT}) instead
 * of blocking the partition forever. Deserialization / illegal-argument failures are treated as
 * non-retryable (a redelivery can never fix them) and go straight to the DLT.
 *
 * <p>Spring Boot wires the single {@link DefaultErrorHandler} bean into the auto-configured Kafka
 * listener container factory.
 */
@Configuration
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KafkaErrorHandlingConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long MAX_RETRIES = 3L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // Publish the failed record to "<originalTopic>.DLT" (single partition 0 to avoid relying on
        // the DLT having the same partition count as the source topic).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(record.topic() + ".DLT", 0));

        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        // Don't waste retries on errors a redelivery can't fix — send straight to the DLT.
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    public NewTopic paymentCallbackDlt() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_CALLBACK + ".DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRefundDlt() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_REFUND + ".DLT").partitions(1).replicas(1).build();
    }
}

