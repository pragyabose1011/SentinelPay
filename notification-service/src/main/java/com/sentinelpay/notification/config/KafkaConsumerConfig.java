package com.sentinelpay.notification.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka consumer error handling for notification-service: retry with exponential
 * backoff, then route to Dead Letter Topic ({@code {topic}.DLT}).
 *
 * <p>Strategy:
 * <ul>
 *   <li>3 retries with 1 s → 2 s → 4 s exponential backoff.</li>
 *   <li>{@link JsonProcessingException} is non-retryable — goes straight to DLT.</li>
 *   <li>Failed records land on {@code payment.transactions.DLT} / {@code kyc.events.DLT}.</li>
 * </ul>
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
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }
}
