package com.sentinelpay.payment.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka consumer error handling: retry with exponential backoff, then DLQ.
 *
 * <p>On consumer failure Spring Kafka will:
 * <ol>
 *   <li>Retry up to 3 times (1 s → 2 s → 4 s) for transient errors.</li>
 *   <li>Route the record to {@code {topic}.DLT} after all retries are exhausted.</li>
 *   <li>Route {@link JsonProcessingException} directly to the DLT (non-retryable —
 *       a malformed payload will never heal itself).</li>
 * </ol>
 *
 * <p>Spring Boot auto-configuration wires this {@link DefaultErrorHandler} bean into
 * the {@code kafkaListenerContainerFactory} automatically.
 */
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    log.error("Routing record to DLT: topic={} partition={} offset={} cause={}",
                            record.topic(), record.partition(), record.offset(), ex.getMessage());
                    return new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", 0);
                });

        ExponentialBackOffWithMaxRetries backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(1_000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(8_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backoff);
        // Malformed JSON is non-retryable — route straight to DLT
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }
}
