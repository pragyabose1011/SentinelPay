package com.sentinelpay.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Event shape published to {@code kyc.events} by kyc-service.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KycEvent {

    private UUID   submissionId;
    private UUID   userId;
    private String userEmail;
    private String fullName;
    private String eventType;  // KYC_SUBMITTED | KYC_APPROVED | KYC_REJECTED
    private String reason;     // populated for KYC_REJECTED
    private Instant occurredAt;
}
