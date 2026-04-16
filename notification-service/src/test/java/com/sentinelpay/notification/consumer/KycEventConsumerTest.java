package com.sentinelpay.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycEventConsumerTest {

    @Mock  NotificationService notificationService;
    @Spy   ObjectMapper        objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks KycEventConsumer consumer;

    private final UUID userId = UUID.randomUUID();
    private final UUID subId  = UUID.randomUUID();

    @Test
    void consume_shouldCallHandleKycEvent_withCorrectEventType() throws Exception {
        String json = "{\"eventType\":\"KYC_APPROVED\",\"userId\":\"" + userId
                + "\",\"submissionId\":\"" + subId + "\",\"userEmail\":\"u@ex.com\",\"fullName\":\"U\"}";

        consumer.consume(record(json));

        ArgumentCaptor<com.sentinelpay.notification.dto.KycEvent> captor =
                ArgumentCaptor.forClass(com.sentinelpay.notification.dto.KycEvent.class);
        verify(notificationService).handleKycEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("KYC_APPROVED");
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void consume_shouldThrow_whenJsonIsMalformed() {
        // Exception propagates — DLQ error handler routes it to kyc.events.DLT
        assertThatThrownBy(() -> consumer.consume(record("{{bad-json")))
                .isInstanceOf(Exception.class);

        verifyNoInteractions(notificationService);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("kyc.events", 0, 0L, userId.toString(), value);
    }
}
