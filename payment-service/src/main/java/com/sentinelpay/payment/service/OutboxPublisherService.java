package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.OutboxEvent;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
 *
 * <p><b>Metrics exported:</b>
 * <ul>
 *   <li>{@code sentinelpay.outbox.parked} — gauge: events parked due to repeated failures</li>
 *   <li>{@code sentinelpay.outbox.publish.errors} — counter: Kafka publish failures</li>
 * </ul>
 */
@Service
@Slf4j
public class OutboxPublisherService {

    private static final int  BATCH_SIZE       = 100;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS     = 300_000L;

    private final OutboxEventRepository      outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter                    publishErrorCounter;

    @Value("${sentinelpay.outbox.max-retries:5}")
    private int maxRetries;

    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate         = kafkaTemplate;

        this.publishErrorCounter = Counter.builder("sentinelpay.outbox.publish.errors")
                .description("Number of failed outbox Kafka publish attempts")
                .register(meterRegistry);

        Gauge.builder("sentinelpay.outbox.parked", outboxEventRepository, repo -> (double) repo.countByParkedTrue())
                .description("Number of outbox events parked after exceeding max retries")
                .register(meterRegistry);
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

    private boolean sendToKafka(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get();
            return true;
        } catch (Exception e) {
            publishErrorCounter.increment();

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
