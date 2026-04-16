package com.sentinelpay.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.notification.dto.TransactionEvent;
import com.sentinelpay.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payment.transactions} events published by payment-service
 * via the transactional outbox pattern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionEventConsumer {

    private final ObjectMapper       objectMapper;
    private final NotificationService notificationService;

    @KafkaListener(
            topics   = "${sentinelpay.kafka.topics.transactions:payment.transactions}",
            groupId  = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) {
        log.debug("Received transaction event: partition={} offset={} key={}",
                record.partition(), record.offset(), record.key());
        try {
            TransactionEvent event = objectMapper.readValue(record.value(), TransactionEvent.class);
            notificationService.handleTransactionEvent(event);
        } catch (Exception e) {
            log.error("Failed to process transaction event at offset {}: {}",
                    record.offset(), e.getMessage(), e);
            // Non-fatal: log and continue so the consumer does not stall.
            // A DLQ strategy should be added for production.
        }
    }
}
