package com.sentinelpay.notification.service;

import com.sentinelpay.notification.domain.Notification;
import com.sentinelpay.notification.dto.KycEvent;
import com.sentinelpay.notification.dto.TransactionEvent;
import com.sentinelpay.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Converts incoming domain events into Notification records and dispatches them.
 *
 * <p>Delivery is stubbed to structured logging — a real implementation would
 * inject a JavaMailSender or Twilio client. The {@code @Async} on each public
 * method prevents the Kafka listener thread from blocking on dispatch latency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EmailService emailService;

    @Async
    @Transactional
    public void handleTransactionEvent(TransactionEvent event) {
        String subject = buildTransactionSubject(event);
        String body    = buildTransactionBody(event);

        // In a real system the recipient address would be fetched from user-service
        // or embedded in the event. We use the wallet ID as a placeholder key.
        String recipient = "user+" + event.getSenderWalletId() + "@sentinelpay.internal";

        Notification notification = Notification.builder()
                .recipient(recipient)
                .channel(Notification.Channel.EMAIL)
                .subject(subject)
                .body(body)
                .eventType("TRANSACTION_" + event.getStatus())
                .referenceId(event.getTransactionId() != null ? event.getTransactionId().toString() : null)
                .build();

        try {
            dispatch(notification, subject, body);
        } catch (Exception e) {
            notification.setStatus(Notification.Status.FAILED);
            notification.setFailureReason(e.getMessage());
            log.error("Notification dispatch failed for transaction {}: {}",
                    event.getTransactionId(), e.getMessage());
        }
        notificationRepository.save(notification);
    }

    @Async
    @Transactional
    public void handleKycEvent(KycEvent event) {
        String subject = buildKycSubject(event);
        String body    = buildKycBody(event);
        String recipient = event.getUserEmail() != null
                ? event.getUserEmail()
                : "user+" + event.getUserId() + "@sentinelpay.internal";

        Notification notification = Notification.builder()
                .userId(event.getUserId())
                .recipient(recipient)
                .channel(Notification.Channel.EMAIL)
                .subject(subject)
                .body(body)
                .eventType(event.getEventType())
                .referenceId(event.getSubmissionId() != null ? event.getSubmissionId().toString() : null)
                .build();

        try {
            dispatch(notification, subject, body);
        } catch (Exception e) {
            notification.setStatus(Notification.Status.FAILED);
            notification.setFailureReason(e.getMessage());
            log.error("Notification dispatch failed for KYC event {} user {}: {}",
                    event.getEventType(), event.getUserId(), e.getMessage());
        }
        notificationRepository.save(notification);
    }

    // -------------------------------------------------------------------------
    // Dispatch — real email via JavaMailSender (Mailhog in dev)
    // -------------------------------------------------------------------------

    private void dispatch(Notification notification, String subject, String body) {
        emailService.send(notification.getRecipient(), subject, body);
        notification.setStatus(Notification.Status.SENT);
        notification.setSentAt(Instant.now());
    }

    // -------------------------------------------------------------------------
    // Message builders
    // -------------------------------------------------------------------------

    private String buildTransactionSubject(TransactionEvent e) {
        return switch (e.getStatus()) {
            case "COMPLETED" -> "Payment confirmed — " + e.getAmount() + " " + e.getCurrency();
            case "FAILED"    -> "Payment failed — " + e.getAmount() + " " + e.getCurrency();
            case "REVERSED"  -> "Payment reversed — " + e.getAmount() + " " + e.getCurrency();
            default          -> "Payment update — " + e.getStatus();
        };
    }

    private String buildTransactionBody(TransactionEvent e) {
        return String.format(
                "Your %s of %s %s has status: %s. Transaction ID: %s.",
                e.getType(), e.getAmount(), e.getCurrency(), e.getStatus(), e.getTransactionId());
    }

    private String buildKycSubject(KycEvent e) {
        return switch (e.getEventType()) {
            case "KYC_SUBMITTED" -> "KYC verification received";
            case "KYC_APPROVED"  -> "KYC verification approved";
            case "KYC_REJECTED"  -> "KYC verification rejected";
            default              -> "KYC update";
        };
    }

    private String buildKycBody(KycEvent e) {
        String name = e.getFullName() != null ? e.getFullName() : "Valued customer";
        return switch (e.getEventType()) {
            case "KYC_SUBMITTED" -> String.format(
                    "Hi %s, we have received your KYC submission (ID: %s) and will review it shortly.",
                    name, e.getSubmissionId());
            case "KYC_APPROVED" -> String.format(
                    "Hi %s, your KYC verification has been approved. You now have full account access.",
                    name);
            case "KYC_REJECTED" -> String.format(
                    "Hi %s, your KYC verification was not approved. Reason: %s. Please resubmit.",
                    name, e.getReason() != null ? e.getReason() : "see support");
            default -> "Your KYC status has been updated.";
        };
    }
}
