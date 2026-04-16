package com.sentinelpay.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail entry — never updated or deleted after creation.
 *
 * <p>Records every significant state change in the system: who triggered it,
 * the entity affected, and JSON snapshots before and after.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_entity",     columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_actor",      columnList = "actor_id"),
        @Index(name = "idx_audit_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Domain class name, e.g. "User", "Wallet", "Transaction". */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    /** UUID of the affected entity. */
    @Column(name = "entity_id", nullable = false, length = 128)
    private String entityId;

    /** Action label, e.g. "CREATE", "KYC_VERIFY", "STATUS_CHANGE", "FRAUD_BLOCK". */
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    /** UUID of the user who triggered the change. Null for system-initiated events. */
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
