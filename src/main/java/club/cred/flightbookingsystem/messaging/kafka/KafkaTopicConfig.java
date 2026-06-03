package club.cred.flightbookingsystem.messaging.kafka;

import club.cred.flightbookingsystem.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the Kafka topics used by the booking lifecycle (created on startup via KafkaAdmin). */
@Configuration
@ConditionalOnProperty(prefix = "flightbooking.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentCallbackTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_CALLBACK).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentTimeoutTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_TIMEOUT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRefundTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_REFUND).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_EVENTS).partitions(3).replicas(1).build();
    }
}

