package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.domain.WebhookRegistration;
import com.sentinelpay.payment.dto.WebhookRequest;
import com.sentinelpay.payment.service.WebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST API for webhook management.
 *
 * <pre>
 * POST   /api/v1/webhooks                     — register a webhook
 * GET    /api/v1/webhooks?userId={id}         — list active webhooks for a user
 * DELETE /api/v1/webhooks/{webhookId}         — deactivate a webhook
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping
    public ResponseEntity<WebhookRegistration> register(
            @Valid @RequestBody WebhookRequest request,
            @AuthenticationPrincipal UUID actorId) {
        WebhookRegistration reg = webhookService.register(request, actorId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(reg.getId()).toUri();
        // Strip secret from response
        reg.setSecret(null);
        return ResponseEntity.created(location).body(reg);
    }

    @GetMapping
    public ResponseEntity<List<WebhookRegistration>> list(
            @RequestParam UUID userId,
            @AuthenticationPrincipal UUID actorId) {
        List<WebhookRegistration> list = webhookService.listForUser(userId, actorId);
        list.forEach(w -> w.setSecret(null)); // never expose secrets
        return ResponseEntity.ok(list);
    }

    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID webhookId,
            @AuthenticationPrincipal UUID actorId) {
        webhookService.deactivate(webhookId, actorId);
        return ResponseEntity.noContent().build();
    }
}
