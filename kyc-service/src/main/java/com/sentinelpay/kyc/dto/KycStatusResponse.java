package com.sentinelpay.kyc.dto;

import com.sentinelpay.kyc.domain.KycSubmission;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class KycStatusResponse {

    private UUID   submissionId;
    private UUID   userId;
    private String userEmail;
    private String fullName;
    private String countryCode;
    private KycSubmission.DocumentType documentType;
    private String documentNumber;
    private KycSubmission.Status status;
    private String rejectionReason;
    private Instant createdAt;
    private Instant reviewedAt;

    public static KycStatusResponse from(KycSubmission s) {
        return KycStatusResponse.builder()
                .submissionId(s.getId())
                .userId(s.getUserId())
                .userEmail(s.getUserEmail())
                .fullName(s.getFullName())
                .countryCode(s.getCountryCode())
                .documentType(s.getDocumentType())
                .documentNumber(s.getDocumentNumber())
                .status(s.getStatus())
                .rejectionReason(s.getRejectionReason())
                .createdAt(s.getCreatedAt())
                .reviewedAt(s.getReviewedAt())
                .build();
    }
}
