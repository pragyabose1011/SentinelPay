package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.AuditLog;
import com.sentinelpay.payment.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes immutable audit log entries.
 *
 * <p>Audit writes use {@code REQUIRES_NEW} so a rollback of the calling transaction
 * never suppresses the audit record — we always want to know what was attempted.
 * The {@code @Async} variant is available for fire-and-forget use cases where
 * latency matters more than strict ordering.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Records an audit event synchronously in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(String entityType, String entityId, String action,
                           UUID actorId, String oldValue, String newValue) {
        AuditLog entry = AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .actorId(actorId)
                .oldValue(oldValue)
                .newValue(newValue)
                .build();
        AuditLog saved = auditLogRepository.save(entry);
        log.debug("Audit: {} {} {} by {}", action, entityType, entityId, actorId);
        return saved;
    }

    /**
     * Fire-and-forget async variant — does not block the caller.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAsync(String entityType, String entityId, String action,
                            UUID actorId, String oldValue, String newValue) {
        record(entityType, entityId, action, actorId, oldValue, newValue);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getEntityHistory(String entityType, String entityId, Pageable pageable) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType, entityId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getActorHistory(UUID actorId, Pageable pageable) {
        return auditLogRepository.findByActorIdOrderByCreatedAtDesc(actorId, pageable);
    }
}
