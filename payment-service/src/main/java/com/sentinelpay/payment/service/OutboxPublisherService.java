package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.OutboxEvent;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Polls the outbox table and publishes pending events to Kafka.
 *
 * <p><b>Retry strategy:</b> on each Kafka failure the event's {@code retry_count}
 * is incremented and {@code next_retry_at} is set to
 * {@code now + initialBackoffMs * 2^retryCount} (capped at {@code maxBackoffMs}).
 * After {@code maxRetries} failures the event is <em>parked</em>: it is excluded from
 * future polls and must be resolved manually (re-queue or discard).
 *
 * <p><b>Guarantees:</b> at-least-once delivery — the outbox row is only marked processed
 * after the Kafka send completes. A crash between send and mark will re-publish on the next
 * poll cycle; consumers must be idempotent.
 */
@Service
@Slf4j
public class OutboxPublisherService {

    private static final int BATCH_SIZE = 100;

    /** Initial backoff after the first failure (1 second). */
    private static final long INITIAL_BACKOFF_MS = 1_000L;

    /** Maximum backoff between retries (5 minutes). */
    private static final long MAX_BACKOFF_MS = 300_000L;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${sentinelpay.outbox.max-retries:5}")
    private int maxRetries;

    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.poll-interval-ms:5000}")
    @Transactional
    public void publishPendingEvents() {
        Instant now = Instant.now();
        List<OutboxEvent> pending = outboxEventRepository
                .findPublishableEvents(now, PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Outbox: publishing {} event(s)", pending.size());

        List<UUID> published = pending.stream()
                .filter(this::sendToKafka)
                .map(OutboxEvent::getId)
                .collect(Collectors.toList());

        if (!published.isEmpty()) {
            outboxEventRepository.markAsProcessed(published, Instant.now());
            log.info("Outbox: marked {} event(s) as processed", published.size());
        }
    }

    /**
     * Attempts to send the event to Kafka.
     *
     * @return {@code true} if the send succeeded, {@code false} otherwise
     */
    private boolean sendToKafka(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get();
            return true;
        } catch (Exception e) {
            int newRetryCount = event.getRetryCount() + 1;
            event.setRetryCount(newRetryCount);

            if (newRetryCount >= maxRetries) {
                event.setParked(true);
                log.error("Outbox: event id={} topic={} PARKED after {} retries — manual intervention required",
                        event.getId(), event.getTopic(), maxRetries);
            } else {
                long backoffMs = Math.min(INITIAL_BACKOFF_MS * (1L << newRetryCount), MAX_BACKOFF_MS);
                event.setNextRetryAt(Instant.now().plusMillis(backoffMs));
                log.warn("Outbox: failed to publish event id={} topic={} retry={}/{} nextRetry=+{}ms cause={}",
                        event.getId(), event.getTopic(), newRetryCount, maxRetries,
                        backoffMs, e.getMessage());
            }

            outboxEventRepository.save(event);
            return false;
        }
    }
}
