package com.sentinelpay.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A client-registered URL that receives real-time event callbacks.
 *
 * <p>Events are comma-separated (e.g. {@code TRANSACTION_COMPLETED,FRAUD_ALERT}).
 * Each delivery includes an {@code X-SentinelPay-Signature} header — an
 * HMAC-SHA256 of the payload body signed with {@link #secret}.
 */
@Entity
@Table(name = "webhook_registrations", indexes = {
        @Index(name = "idx_webhook_user",   columnList = "user_id"),
        @Index(name = "idx_webhook_active", columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    /** CSV of subscribed event types, e.g. {@code TRANSACTION_COMPLETED,FRAUD_ALERT}. */
    @Column(name = "events", nullable = false, columnDefinition = "text")
    private String events;

    /** HMAC-SHA256 signing secret. Stored; never returned via API. */
    @Column(name = "secret", length = 255)
    private String secret;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void onUpdate() { this.updatedAt = Instant.now(); }
}
