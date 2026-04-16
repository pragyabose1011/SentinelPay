package com.sentinelpay.payment.repository;

import com.sentinelpay.payment.domain.WebhookRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookRepository extends JpaRepository<WebhookRegistration, UUID> {

    List<WebhookRegistration> findByUserIdAndActiveTrue(UUID userId);

    /**
     * Returns all active webhooks whose events CSV contains the given event type.
     * Used by the dispatcher to fan out to registered endpoints.
     */
    @Query("SELECT w FROM WebhookRegistration w WHERE w.active = true AND w.events LIKE %:eventType%")
    List<WebhookRegistration> findActiveByEventType(@Param("eventType") String eventType);
}
