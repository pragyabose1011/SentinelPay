package com.sentinelpay.kyc.service;

import com.sentinelpay.kyc.domain.KycSubmission;
import com.sentinelpay.kyc.dto.KycEvent;
import com.sentinelpay.kyc.dto.KycStatusResponse;
import com.sentinelpay.kyc.dto.KycSubmitRequest;
import com.sentinelpay.kyc.repository.KycSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycService {

    private final KycSubmissionRepository kycSubmissionRepository;
    private final KycEventPublisher       kycEventPublisher;

    // -------------------------------------------------------------------------
    // User-facing
    // -------------------------------------------------------------------------

    /**
     * Accepts a new KYC submission for the given user.
     * Rejects if the user already has a PENDING or APPROVED submission.
     */
    @Transactional
    public KycStatusResponse submit(KycSubmitRequest request, UUID userId) {
        if (kycSubmissionRepository.existsByUserIdAndStatus(userId, KycSubmission.Status.APPROVED)) {
            throw new IllegalStateException("User " + userId + " is already KYC-verified.");
        }
        if (kycSubmissionRepository.existsByUserIdAndStatus(userId, KycSubmission.Status.PENDING)) {
            throw new IllegalStateException(
                    "A KYC submission is already pending review for user " + userId + ".");
        }

        KycSubmission submission = KycSubmission.builder()
                .userId(userId)
                .userEmail(request.getUserEmail())
                .fullName(request.getFullName())
                .countryCode(request.getCountryCode().toUpperCase())
                .documentType(request.getDocumentType())
                .documentNumber(request.getDocumentNumber())
                .documentUrl(request.getDocumentUrl())
                .build();

        submission = kycSubmissionRepository.save(submission);
        log.info("KYC submitted: submissionId={} userId={}", submission.getId(), userId);

        kycEventPublisher.publish(KycEvent.builder()
                .eventType("KYC_SUBMITTED")
                .submissionId(submission.getId())
                .userId(userId)
                .userEmail(submission.getUserEmail())
                .fullName(submission.getFullName())
                .occurredAt(Instant.now())
                .build());

        return KycStatusResponse.from(submission);
    }

    /** Returns the latest submission for a given user. */
    @Transactional(readOnly = true)
    public KycStatusResponse getStatus(UUID userId) {
        KycSubmission submission = kycSubmissionRepository
                .findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No KYC submission found for user " + userId));
        return KycStatusResponse.from(submission);
    }

    // -------------------------------------------------------------------------
    // Admin-facing
    // -------------------------------------------------------------------------

    @Transactional
    public KycStatusResponse approve(UUID submissionId, UUID adminId) {
        KycSubmission submission = findOrThrow(submissionId);
        guardReviewable(submission);

        submission.setStatus(KycSubmission.Status.APPROVED);
        submission.setReviewedBy(adminId);
        submission.setReviewedAt(Instant.now());
        submission = kycSubmissionRepository.save(submission);

        log.info("KYC approved: submissionId={} by adminId={}", submissionId, adminId);

        kycEventPublisher.publish(KycEvent.builder()
                .eventType("KYC_APPROVED")
                .submissionId(submission.getId())
                .userId(submission.getUserId())
                .userEmail(submission.getUserEmail())
                .fullName(submission.getFullName())
                .occurredAt(Instant.now())
                .build());

        return KycStatusResponse.from(submission);
    }

    @Transactional
    public KycStatusResponse reject(UUID submissionId, UUID adminId, String reason) {
        KycSubmission submission = findOrThrow(submissionId);
        guardReviewable(submission);

        submission.setStatus(KycSubmission.Status.REJECTED);
        submission.setReviewedBy(adminId);
        submission.setReviewedAt(Instant.now());
        submission.setRejectionReason(reason);
        submission = kycSubmissionRepository.save(submission);

        log.info("KYC rejected: submissionId={} by adminId={} reason={}",
                submissionId, adminId, reason);

        kycEventPublisher.publish(KycEvent.builder()
                .eventType("KYC_REJECTED")
                .submissionId(submission.getId())
                .userId(submission.getUserId())
                .userEmail(submission.getUserEmail())
                .fullName(submission.getFullName())
                .reason(reason)
                .occurredAt(Instant.now())
                .build());

        return KycStatusResponse.from(submission);
    }

    /** Returns paginated list of submissions in a given status (for admin review queue). */
    @Transactional(readOnly = true)
    public Page<KycStatusResponse> listByStatus(KycSubmission.Status status, Pageable pageable) {
        return kycSubmissionRepository
                .findByStatusOrderByCreatedAtAsc(status, pageable)
                .map(KycStatusResponse::from);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private KycSubmission findOrThrow(UUID id) {
        return kycSubmissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("KYC submission not found: " + id));
    }

    private void guardReviewable(KycSubmission submission) {
        if (submission.getStatus() == KycSubmission.Status.APPROVED) {
            throw new IllegalStateException(
                    "Submission " + submission.getId() + " is already APPROVED.");
        }
        if (submission.getStatus() == KycSubmission.Status.REJECTED) {
            throw new IllegalStateException(
                    "Submission " + submission.getId() + " is already REJECTED. Create a new submission.");
        }
    }
}
