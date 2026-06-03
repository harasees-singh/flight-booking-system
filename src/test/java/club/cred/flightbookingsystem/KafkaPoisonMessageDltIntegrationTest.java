package club.cred.flightbookingsystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the consumer error policy: a poison (undeserializable) message on {@code payment.callback}
 * does not block the partition — it is routed to the dead-letter topic {@code payment.callback.DLT}
 * by the {@code DefaultErrorHandler} + {@code DeadLetterPublishingRecoverer}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("itkafka")
@EmbeddedKafka(partitions = 1, topics = {
        "payment.callback", "payment.refund", "booking.events",
        "payment.callback.DLT", "payment.refund.DLT"})
class KafkaPoisonMessageDltIntegrationTest {

    @Autowired private EmbeddedKafkaBroker broker;

    @Test
    void poisonPaymentCallback_isRoutedToDeadLetterTopic() {
        // Produce an undeserializable payload to payment.callback.
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer =
                     new DefaultKafkaProducerFactory<String, String>(producerProps).createProducer()) {
            producer.send(new ProducerRecord<>("payment.callback", "1", "{ this is not valid json"));
            producer.flush();
        }

        // It should appear on the DLT (deserialization failures are non-retryable → straight to DLT).
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-verify");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "payment.callback.DLT");
            ConsumerRecord<String, String> dlt =
                    KafkaTestUtils.getSingleRecord(consumer, "payment.callback.DLT", Duration.ofSeconds(20));

            assertThat(dlt).isNotNull();
            // The recoverer stamps the original topic on a DLT header — serializer-independent assertion.
            Header originalTopic = dlt.headers().lastHeader("kafka_dlt-original-topic");
            assertThat(originalTopic).isNotNull();
            assertThat(new String(originalTopic.value(), StandardCharsets.UTF_8))
                    .isEqualTo("payment.callback");
        }
    }
}

