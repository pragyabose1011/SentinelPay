package com.sentinelpay.kyc.service;

import com.sentinelpay.kyc.domain.KycSubmission;
import com.sentinelpay.kyc.dto.KycEvent;
import com.sentinelpay.kyc.dto.KycStatusResponse;
import com.sentinelpay.kyc.dto.KycSubmitRequest;
import com.sentinelpay.kyc.repository.KycSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KycService}. Repository and event publisher are mocked.
 */
@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock KycSubmissionRepository kycSubmissionRepository;
    @Mock KycEventPublisher       kycEventPublisher;

    @InjectMocks KycService kycService;

    private UUID userId;
    private UUID submissionId;
    private KycSubmission pendingSubmission;

    @BeforeEach
    void setUp() {
        userId       = UUID.randomUUID();
        submissionId = UUID.randomUUID();
        pendingSubmission = KycSubmission.builder()
                .id(submissionId)
                .userId(userId)
                .userEmail("alice@example.com")
                .fullName("Alice")
                .countryCode("IN")
                .documentType(KycSubmission.DocumentType.PASSPORT)
                .documentNumber("P1234567")
                .status(KycSubmission.Status.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // =========================================================================
    // submit
    // =========================================================================

    @Test
    void submit_shouldSaveSubmissionAndPublishEvent() {
        when(kycSubmissionRepository.existsByUserIdAndStatus(userId, KycSubmission.Status.APPROVED)).thenReturn(false);
        when(kycSubmissionRepository.existsByUserIdAndStatus(userId, KycSubmission.Status.PENDING)).thenReturn(false);
        when(kycSubmissionRepository.save(any())).thenReturn(pendingSubmission);

        KycStatusResponse response = kycService.submit(buildRequest(), userId);

        assertThat(response.getStatus()).isEqualTo(KycSubmission.Status.PENDING);
        assertThat(response.getUserId()).isEqualTo(userId);

        ArgumentCaptor<KycEvent> eventCaptor = ArgumentCaptor.forClass(KycEvent.class);
        verify(kycEventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("KYC_SUBMITTED");
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void submit_shouldThrow_whenAlreadyApproved() {
        when(kycSubmissionRepository.existsByUserIdAndStatus(userId, KycSubmission.Status.APPROVED)).thenReturn(true);

        assertThatThrownBy(() -> kycService.submit(buildRequest(), userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already KYC-verified");
    }

    @Test
    void submit_shouldThrow_whenPendingExists() {
        when(kycSubmissionRepository.existsByUserIdAndStatus(userId, KycSubmission.Status.APPROVED)).thenReturn(false);
        when(kycSubmissionRepository.existsByUserIdAndStatus(userId, KycSubmission.Status.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> kycService.submit(buildRequest(), userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already pending");
    }

    // =========================================================================
    // getStatus
    // =========================================================================

    @Test
    void getStatus_shouldReturnLatestSubmission() {
        when(kycSubmissionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(pendingSubmission));

        KycStatusResponse response = kycService.getStatus(userId);

        assertThat(response.getSubmissionId()).isEqualTo(submissionId);
        assertThat(response.getStatus()).isEqualTo(KycSubmission.Status.PENDING);
    }

    @Test
    void getStatus_shouldThrow_whenNoSubmissionFound() {
        when(kycSubmissionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.getStatus(userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No KYC submission found");
    }

    // =========================================================================
    // approve
    // =========================================================================

    @Test
    void approve_shouldSetApprovedAndPublishEvent() {
        UUID adminId = UUID.randomUUID();
        when(kycSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(pendingSubmission));
        when(kycSubmissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KycStatusResponse response = kycService.approve(submissionId, adminId);

        assertThat(response.getStatus()).isEqualTo(KycSubmission.Status.APPROVED);

        ArgumentCaptor<KycEvent> captor = ArgumentCaptor.forClass(KycEvent.class);
        verify(kycEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("KYC_APPROVED");
    }

    @Test
    void approve_shouldThrow_whenSubmissionNotFound() {
        when(kycSubmissionRepository.findById(submissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.approve(submissionId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KYC submission not found");
    }

    @Test
    void approve_shouldThrow_whenAlreadyApproved() {
        pendingSubmission.setStatus(KycSubmission.Status.APPROVED);
        when(kycSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(pendingSubmission));

        assertThatThrownBy(() -> kycService.approve(submissionId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already APPROVED");
    }

    // =========================================================================
    // reject
    // =========================================================================

    @Test
    void reject_shouldSetRejectedReasonAndPublishEvent() {
        UUID adminId = UUID.randomUUID();
        when(kycSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(pendingSubmission));
        when(kycSubmissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KycStatusResponse response = kycService.reject(submissionId, adminId, "Documents unclear");

        assertThat(response.getStatus()).isEqualTo(KycSubmission.Status.REJECTED);
        assertThat(response.getRejectionReason()).isEqualTo("Documents unclear");

        ArgumentCaptor<KycEvent> captor = ArgumentCaptor.forClass(KycEvent.class);
        verify(kycEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("KYC_REJECTED");
    }

    @Test
    void reject_shouldThrow_whenAlreadyRejected() {
        pendingSubmission.setStatus(KycSubmission.Status.REJECTED);
        when(kycSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(pendingSubmission));

        assertThatThrownBy(() -> kycService.reject(submissionId, UUID.randomUUID(), "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already REJECTED");
    }

    // =========================================================================
    // listByStatus
    // =========================================================================

    @Test
    void listByStatus_shouldReturnPageOfResponses() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(kycSubmissionRepository.findByStatusOrderByCreatedAtAsc(
                eq(KycSubmission.Status.PENDING), any()))
                .thenReturn(new PageImpl<>(List.of(pendingSubmission), pageable, 1));

        var page = kycService.listByStatus(KycSubmission.Status.PENDING, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(KycSubmission.Status.PENDING);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KycSubmitRequest buildRequest() {
        KycSubmitRequest req = new KycSubmitRequest();
        req.setUserEmail("alice@example.com");
        req.setFullName("Alice");
        req.setCountryCode("IN");
        req.setDocumentType(KycSubmission.DocumentType.PASSPORT);
        req.setDocumentNumber("P1234567");
        return req;
    }
}
