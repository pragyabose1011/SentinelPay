package com.sentinelpay.notification.dto;

import com.sentinelpay.notification.domain.Notification;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class NotificationResponse {

    private UUID   id;
    private UUID   userId;
    private String recipient;
    private String channel;
    private String status;
    private String subject;
    private String body;
    private String eventType;
    private String referenceId;
    private Instant sentAt;
    private Instant createdAt;

    public static NotificationResponse from(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .userId(n.getUserId())
                .recipient(n.getRecipient())
                .channel(n.getChannel() != null ? n.getChannel().name() : null)
                .status(n.getStatus() != null ? n.getStatus().name() : null)
                .subject(n.getSubject())
                .body(n.getBody())
                .eventType(n.getEventType())
                .referenceId(n.getReferenceId())
                .sentAt(n.getSentAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
