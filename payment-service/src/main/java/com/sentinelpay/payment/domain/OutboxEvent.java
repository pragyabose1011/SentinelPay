package com.sentinelpay.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox table — guarantees at-least-once Kafka delivery.
 *
 * <p>A background scheduler polls unpublished events and publishes them to Kafka.
 * Once published, the event is marked as processed. This prevents message loss
 * in case the application crashes between completing a DB write and sending to Kafka.
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_processed_created", columnList = "processed, created_at"),
        @Index(name = "idx_outbox_aggregate_id", columnList = "aggregate_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Domain entity the event is about (e.g., "Transaction", "Wallet").
     */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /**
     * ID of the aggregate instance this event belongs to.
     */
    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    /**
     * Kafka topic to publish to.
     */
    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    /**
     * Event type (e.g., "TRANSACTION_COMPLETED", "FRAUD_ALERT").
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * JSON-serialized event payload.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "processed", nullable = false)
    @Builder.Default
    private boolean processed = false;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
