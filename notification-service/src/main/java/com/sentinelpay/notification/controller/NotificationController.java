package com.sentinelpay.notification.controller;

import com.sentinelpay.notification.dto.NotificationResponse;
import com.sentinelpay.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Exposes read-only access to persisted notification records.
 *
 * <p>Authentication is enforced at the api-gateway layer (JWT filter),
 * not at this service directly — the service is not exposed externally.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    /**
     * Returns paginated notifications for a user.
     * GET /api/v1/notifications?userId={userId}
     */
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> list(
            @RequestParam UUID userId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                        .map(NotificationResponse::from));
    }
}
