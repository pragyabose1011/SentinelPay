package com.sentinelpay.notification.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted record of every notification dispatched by the system.
 * Provides an audit trail and allows re-sending failed notifications.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notif_user_id",    columnList = "user_id"),
        @Index(name = "idx_notif_channel",    columnList = "channel"),
        @Index(name = "idx_notif_status",     columnList = "status"),
        @Index(name = "idx_notif_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** User ID from payment-service (may be null for system-generated events). */
    @Column(name = "user_id")
    private UUID userId;

    /** Recipient address (email, phone number, etc.). */
    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /** Source event type (e.g. TRANSACTION_COMPLETED, KYC_APPROVED). */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Reference ID from the source event (transaction ID, submission ID, etc.). */
    @Column(name = "reference_id", length = 128)
    private String referenceId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public enum Channel {
        EMAIL, SMS, PUSH
    }

    public enum Status {
        PENDING, SENT, FAILED
    }
}
