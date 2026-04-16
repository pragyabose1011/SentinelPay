package com.sentinelpay.kyc.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a user's KYC document submission and its review lifecycle.
 *
 * <p>State machine: PENDING → UNDER_REVIEW → APPROVED | REJECTED
 */
@Entity
@Table(name = "kyc_submissions", indexes = {
        @Index(name = "idx_kyc_user_id",    columnList = "user_id"),
        @Index(name = "idx_kyc_status",     columnList = "status"),
        @Index(name = "idx_kyc_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** UUID from payment-service — the caller's identity. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    /** ISO 3166-1 alpha-2 country code (e.g. IN, US). */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    /** Reference number printed on the document (passport no., DL no., etc.). */
    @Column(name = "document_number", nullable = false, length = 100)
    private String documentNumber;

    /**
     * URL / S3 key of the uploaded document image.
     * In this reference implementation only the reference is stored; actual
     * file upload/storage is outside the service boundary.
     */
    @Column(name = "document_url", length = 512)
    private String documentUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    /** Admin who reviewed this submission. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public enum Status {
        PENDING, UNDER_REVIEW, APPROVED, REJECTED
    }

    public enum DocumentType {
        PASSPORT, NATIONAL_ID, DRIVING_LICENSE, RESIDENCE_PERMIT
    }
}
