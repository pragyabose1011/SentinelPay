package com.sentinelpay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Listens to {@code kyc.events} published by kyc-service and automatically
 * sets {@code users.kyc_verified = true} when a KYC_APPROVED event arrives.
 *
 * <p>This keeps the two services decoupled: kyc-service never calls
 * payment-service directly; it only publishes an event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KycEventConsumer {

    private final UserRepository userRepository;
    private final ObjectMapper   objectMapper;

    @KafkaListener(
            topics   = "${sentinelpay.kafka.topics.kyc:kyc.events}",
            groupId  = "payment-service-kyc",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(ConsumerRecord<String, String> record) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);
            String eventType = (String) payload.get("eventType");

            if (!"KYC_APPROVED".equals(eventType)) {
                return;   // only KYC_APPROVED changes anything in payment-service
            }

            String userIdStr = (String) payload.get("userId");
            if (userIdStr == null) {
                log.warn("KYC_APPROVED event missing userId at offset {}", record.offset());
                return;
            }

            UUID userId = UUID.fromString(userIdStr);
            int updated = userRepository.setKycVerified(userId, true);
            if (updated > 0) {
                log.info("KYC approved: userId={} — kyc_verified set to true", userId);
            } else {
                log.warn("KYC_APPROVED for unknown userId={}", userId);
            }
        } catch (Exception e) {
            log.error("Failed to process KYC event at offset {}: {}",
                    record.offset(), e.getMessage(), e);
        }
    }
}
