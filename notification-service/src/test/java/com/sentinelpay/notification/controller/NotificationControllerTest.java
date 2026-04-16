package com.sentinelpay.notification.controller;

import com.sentinelpay.notification.domain.Notification;
import com.sentinelpay.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link NotificationController}.
 *
 * <p>No Spring Security in notification-service — the endpoint is internal,
 * protected at the api-gateway layer.
 */
@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NotificationRepository notificationRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID notifId = UUID.randomUUID();

    @Test
    void list_shouldReturn200WithPagedNotifications() throws Exception {
        PageImpl<Notification> page = new PageImpl<>(
                List.of(buildNotification()),
                PageRequest.of(0, 20), 1);
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/notifications")
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(notifId.toString()))
                .andExpect(jsonPath("$.content[0].eventType").value("KYC_SUBMITTED"))
                .andExpect(jsonPath("$.content[0].status").value("SENT"));
    }

    @Test
    void list_shouldReturn200WithEmptyPage_whenNoNotifications() throws Exception {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void list_shouldReturn400_whenUserIdMissing() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Notification buildNotification() {
        return Notification.builder()
                .id(notifId)
                .userId(userId)
                .recipient("alice@example.com")
                .channel(Notification.Channel.EMAIL)
                .status(Notification.Status.SENT)
                .subject("KYC verification received")
                .body("Hi Alice, we received your KYC submission.")
                .eventType("KYC_SUBMITTED")
                .referenceId(UUID.randomUUID().toString())
                .sentAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }
}
