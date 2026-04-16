package com.sentinelpay.kyc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.kyc.dto.KycEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes KYC lifecycle events to the {@code kyc.events} Kafka topic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KycEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    @Value("${sentinelpay.kafka.topics.kyc:kyc.events}")
    private String kycTopic;

    public void publish(KycEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(kycTopic, event.getUserId().toString(), payload);
            log.info("Published KYC event: type={} userId={} submissionId={}",
                    event.getEventType(), event.getUserId(), event.getSubmissionId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize KYC event for submissionId={}: {}",
                    event.getSubmissionId(), e.getMessage(), e);
            throw new IllegalStateException("KYC event serialization failed", e);
        }
    }
}
