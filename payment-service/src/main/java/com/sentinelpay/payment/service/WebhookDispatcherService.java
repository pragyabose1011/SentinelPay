package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.WebhookRegistration;
import com.sentinelpay.payment.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * Listens to the {@code payment.transactions} Kafka topic and fans out
 * event payloads to all registered, matching webhooks.
 *
 * <p>Each delivery includes:
 * <ul>
 *   <li>{@code X-SentinelPay-Event}     — the event type (e.g. TRANSACTION_COMPLETED)</li>
 *   <li>{@code X-SentinelPay-Signature} — HMAC-SHA256 hex signature (if webhook has a secret)</li>
 * </ul>
 *
 * <p>Delivery is best-effort (at-least-once via Kafka retry). Failed deliveries
 * are logged; a dead-letter queue or retry table can be added later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcherService {

    private final WebhookRepository webhookRepository;
    private final RestClient        restClient;

    @KafkaListener(topics = "payment.transactions", groupId = "webhook-dispatcher")
    public void onTransactionEvent(ConsumerRecord<String, String> record) {
        String payload   = record.value();
        String eventType = extractEventType(payload);
        if (eventType == null) return;

        List<WebhookRegistration> targets = webhookRepository.findActiveByEventType(eventType);
        if (targets.isEmpty()) return;

        log.debug("Dispatching event={} to {} webhook(s)", eventType, targets.size());
        for (WebhookRegistration webhook : targets) {
            dispatch(webhook, eventType, payload);
        }
    }

    private void dispatch(WebhookRegistration webhook, String eventType, String payload) {
        try {
            var spec = restClient.post()
                    .uri(webhook.getUrl())
                    .header("Content-Type", "application/json")
                    .header("X-SentinelPay-Event", eventType)
                    .body(payload);

            if (webhook.getSecret() != null) {
                spec = spec.header("X-SentinelPay-Signature", sign(payload, webhook.getSecret()));
            }
            spec.retrieve().toBodilessEntity();
            log.debug("Webhook delivered: id={} event={}", webhook.getId(), eventType);
        } catch (Exception e) {
            log.warn("Webhook delivery failed: id={} url={} error={}",
                    webhook.getId(), webhook.getUrl(), e.getMessage());
        }
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Failed to sign webhook payload", e);
            return "";
        }
    }

    private String extractEventType(String payload) {
        // Lightweight extraction from the JSON "status" field set by the outbox publisher
        // e.g. {"status":"COMPLETED",...} → TRANSACTION_COMPLETED
        try {
            int idx = payload.indexOf("\"status\":\"");
            if (idx < 0) return null;
            int start = idx + 10;
            int end   = payload.indexOf('"', start);
            return "TRANSACTION_" + payload.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }
}
