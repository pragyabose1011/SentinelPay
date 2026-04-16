package com.sentinelpay.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a payment transaction.
 *
 * <p>Wallet FK nullability by type:
 * <ul>
 *   <li>TRANSFER / REVERSAL — both sender and receiver are non-null</li>
 *   <li>DEPOSIT             — sender is null (external source), receiver is non-null</li>
 *   <li>WITHDRAWAL          — sender is non-null, receiver is null (external destination)</li>
 * </ul>
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_idempotency_key", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_txn_sender_wallet",   columnList = "sender_wallet_id"),
        @Index(name = "idx_txn_receiver_wallet",  columnList = "receiver_wallet_id"),
        @Index(name = "idx_txn_status",           columnList = "status"),
        @Index(name = "idx_txn_created_at",       columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    /** Null for DEPOSIT transactions (money arrives from outside the system). */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "sender_wallet_id", nullable = true)
    private Wallet senderWallet;

    /** Null for WITHDRAWAL transactions (money leaves the system). */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "receiver_wallet_id", nullable = true)
    private Wallet receiverWallet;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private TransactionType type;

    @Column(name = "description", length = 500)
    private String description;

    /** Reference to the original transaction for REVERSAL type. */
    @Column(name = "reference_transaction_id")
    private UUID referenceTransactionId;

    /** JSON blob: fraud scores, exchange rates, saga state, etc. */
    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public enum TransactionStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, REVERSED, SAGA_COMPENSATING
    }

    public enum TransactionType {
        TRANSFER, DEPOSIT, WITHDRAWAL, REVERSAL
    }
}
