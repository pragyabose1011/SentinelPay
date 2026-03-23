package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.OutboxEvent;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Polls the outbox table and publishes pending events to Kafka.
 *
 * <p>Runs every 5 seconds (configurable via {@code sentinelpay.outbox.poll-interval-ms}).
 * Processed events are marked in the same local transaction to avoid double-publishing
 * in the common case. Kafka delivery is best-effort within the schedule interval;
 * the at-least-once guarantee comes from retrying on the next poll cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherService {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.poll-interval-ms:5000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findByProcessedFalseOrderByCreatedAtAsc(PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Outbox: publishing {} event(s)", pending.size());

        List<OutboxEvent> published = pending.stream()
                .filter(this::sendToKafka)
                .collect(Collectors.toList());

        if (!published.isEmpty()) {
            List<java.util.UUID> ids = published.stream()
                    .map(OutboxEvent::getId)
                    .collect(Collectors.toList());
            outboxEventRepository.markAsProcessed(ids, Instant.now());
            log.info("Outbox: marked {} event(s) as processed", published.size());
        }
    }

    private boolean sendToKafka(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload());
            return true;
        } catch (Exception e) {
            log.warn("Outbox: failed to publish event id={} topic={}: {}",
                    event.getId(), event.getTopic(), e.getMessage());
            event.setRetryCount(event.getRetryCount() + 1);
            outboxEventRepository.save(event);
            return false;
        }
    }
}
