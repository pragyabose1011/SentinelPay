package com.sentinelpay.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.notification.dto.KycEvent;
import com.sentinelpay.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code kyc.events} published by kyc-service (KYC_SUBMITTED,
 * KYC_APPROVED, KYC_REJECTED).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KycEventConsumer {

    private final ObjectMapper        objectMapper;
    private final NotificationService notificationService;

    @KafkaListener(
            topics   = "${sentinelpay.kafka.topics.kyc:kyc.events}",
            groupId  = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        log.debug("Received KYC event: partition={} offset={} key={}",
                record.partition(), record.offset(), record.key());
        KycEvent event = objectMapper.readValue(record.value(), KycEvent.class);
        notificationService.handleKycEvent(event);
    }
}
