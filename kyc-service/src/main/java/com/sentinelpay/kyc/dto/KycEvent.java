package com.sentinelpay.kyc.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload published to the {@code kyc.events} Kafka topic.
 * Consumed by payment-service (to flip kyc_verified) and
 * notification-service (to send email confirmations).
 */
@Data
@Builder
public class KycEvent {

    /** KYC_SUBMITTED | KYC_APPROVED | KYC_REJECTED */
    private String eventType;

    private UUID   submissionId;
    private UUID   userId;
    private String userEmail;
    private String fullName;

    /** Populated only for KYC_REJECTED. */
    private String reason;

    private Instant occurredAt;
}
