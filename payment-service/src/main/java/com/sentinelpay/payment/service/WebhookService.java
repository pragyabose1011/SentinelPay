package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.WebhookRegistration;
import com.sentinelpay.payment.dto.WebhookRequest;
import com.sentinelpay.payment.exception.ForbiddenException;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WebhookRepository webhookRepository;
    private final UserRepository    userRepository;

    @Transactional
    public WebhookRegistration register(WebhookRequest request, UUID actorId) {
        User user = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + actorId));
        WebhookRegistration reg = WebhookRegistration.builder()
                .user(user)
                .url(request.getUrl())
                .events(request.getEvents())
                .secret(request.getSecret())
                .build();
        reg = webhookRepository.save(reg);
        log.info("Webhook registered: id={} userId={} url={}", reg.getId(), actorId, reg.getUrl());
        return reg;
    }

    @Transactional(readOnly = true)
    public List<WebhookRegistration> listForUser(UUID userId, UUID actorId) {
        if (!userId.equals(actorId)) assertAdmin(actorId);
        return webhookRepository.findByUserIdAndActiveTrue(userId);
    }

    @Transactional
    public void deactivate(UUID webhookId, UUID actorId) {
        WebhookRegistration reg = webhookRepository.findById(webhookId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found: " + webhookId));
        if (!reg.getUser().getId().equals(actorId)) assertAdmin(actorId);
        reg.setActive(false);
        webhookRepository.save(reg);
        log.info("Webhook deactivated: id={} by userId={}", webhookId, actorId);
    }

    private void assertAdmin(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + actorId));
        if (actor.getRole() != User.UserRole.ADMIN) throw new ForbiddenException("Access denied.");
    }
}
