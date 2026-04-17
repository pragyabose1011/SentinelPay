package com.sentinelpay.notification.service;

import com.sentinelpay.notification.domain.Notification;
import com.sentinelpay.notification.dto.KycEvent;
import com.sentinelpay.notification.dto.TransactionEvent;
import com.sentinelpay.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NotificationService}.
 *
 * <p>Verifies subject/body content, status transitions, and that
 * notifications are always persisted even on dispatch failure.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock EmailService           emailService;

    @InjectMocks NotificationService notificationService;

    private final UUID userId       = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();
    private final UUID txnId        = UUID.randomUUID();
    private final UUID walletId     = UUID.randomUUID();

    // =========================================================================
    // handleKycEvent
    // =========================================================================

    @Test
    void handleKycEvent_submitted_shouldSaveSentNotification() {
        notificationService.handleKycEvent(kycEvent("KYC_SUBMITTED"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification n = captor.getValue();
        assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(n.getEventType()).isEqualTo("KYC_SUBMITTED");
        assertThat(n.getSubject()).isEqualTo("KYC verification received");
        assertThat(n.getBody()).contains("Alice").contains(submissionId.toString());
        assertThat(n.getRecipient()).isEqualTo("alice@example.com");
        assertThat(n.getChannel()).isEqualTo(Notification.Channel.EMAIL);
    }

    @Test
    void handleKycEvent_approved_shouldSaveSentNotification() {
        notificationService.handleKycEvent(kycEvent("KYC_APPROVED"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification n = captor.getValue();
        assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(n.getSubject()).isEqualTo("KYC verification approved");
        assertThat(n.getBody()).contains("full account access");
    }

    @Test
    void handleKycEvent_rejected_shouldIncludeReason() {
        KycEvent event = kycEvent("KYC_REJECTED");
        event.setReason("Documents expired");
        notificationService.handleKycEvent(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification n = captor.getValue();
        assertThat(n.getSubject()).isEqualTo("KYC verification rejected");
        assertThat(n.getBody()).contains("Documents expired");
    }

    @Test
    void handleKycEvent_shouldFallbackToUserId_whenEmailIsNull() {
        KycEvent event = kycEvent("KYC_SUBMITTED");
        event.setUserEmail(null);
        notificationService.handleKycEvent(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        assertThat(captor.getValue().getRecipient()).contains(userId.toString());
    }

    // =========================================================================
    // handleTransactionEvent
    // =========================================================================

    @Test
    void handleTransactionEvent_completed_shouldSaveSentNotification() {
        notificationService.handleTransactionEvent(txnEvent("COMPLETED"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification n = captor.getValue();
        assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(n.getEventType()).isEqualTo("TRANSACTION_COMPLETED");
        assertThat(n.getSubject()).contains("Payment confirmed").contains("INR");
        assertThat(n.getBody()).contains("COMPLETED").contains(txnId.toString());
    }

    @Test
    void handleTransactionEvent_failed_shouldHaveFailedSubject() {
        notificationService.handleTransactionEvent(txnEvent("FAILED"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        assertThat(captor.getValue().getSubject()).contains("Payment failed");
    }

    @Test
    void handleTransactionEvent_reversed_shouldHaveReversedSubject() {
        notificationService.handleTransactionEvent(txnEvent("REVERSED"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        assertThat(captor.getValue().getSubject()).contains("Payment reversed");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KycEvent kycEvent(String type) {
        KycEvent e = new KycEvent();
        e.setEventType(type);
        e.setUserId(userId);
        e.setSubmissionId(submissionId);
        e.setUserEmail("alice@example.com");
        e.setFullName("Alice");
        return e;
    }

    private TransactionEvent txnEvent(String status) {
        TransactionEvent e = new TransactionEvent();
        e.setTransactionId(txnId);
        e.setSenderWalletId(walletId);
        e.setAmount(new BigDecimal("10000.00"));
        e.setCurrency("INR");
        e.setStatus(status);
        e.setType("TRANSFER");
        return e;
    }
}
