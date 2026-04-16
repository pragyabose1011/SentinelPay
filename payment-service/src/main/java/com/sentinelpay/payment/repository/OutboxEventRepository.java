package com.sentinelpay.payment.repository;

import com.sentinelpay.payment.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Returns the oldest unpublished events up to the given limit.
     * Used by the outbox publisher scheduler.
     */
    List<OutboxEvent> findByProcessedFalseOrderByCreatedAtAsc(Pageable pageable);

    /**
     * Bulk-mark events as processed after successful Kafka publish.
     */
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.processed = true, e.processedAt = :processedAt WHERE e.id IN :ids")
    int markAsProcessed(@Param("ids") List<UUID> ids, @Param("processedAt") Instant processedAt);

    long countByProcessedFalse();

    long countByProcessedTrue();
}
