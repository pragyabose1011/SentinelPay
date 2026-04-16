package com.sentinelpay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.repository.UserRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KycEventConsumer}.
 *
 * <p>Verifies that KYC_APPROVED sets kyc_verified on the user, non-APPROVED
 * event types are ignored, and bad JSON is swallowed without throwing.
 */
@ExtendWith(MockitoExtension.class)
class KycEventConsumerTest {

    @Mock  UserRepository    userRepository;
    @Spy   ObjectMapper      objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @InjectMocks KycEventConsumer consumer;

    private final UUID userId = UUID.randomUUID();

    @Test
    void consume_shouldSetKycVerified_whenKycApproved() throws Exception {
        when(userRepository.setKycVerified(userId, true)).thenReturn(1);

        ConsumerRecord<String, String> record = record(
                "{\"eventType\":\"KYC_APPROVED\",\"userId\":\"" + userId + "\"}");

        consumer.consume(record);

        verify(userRepository).setKycVerified(userId, true);
    }

    @Test
    void consume_shouldIgnore_whenEventTypeIsNotApproved() throws Exception {
        ConsumerRecord<String, String> record = record(
                "{\"eventType\":\"KYC_SUBMITTED\",\"userId\":\"" + userId + "\"}");

        consumer.consume(record);

        verifyNoInteractions(userRepository);
    }

    @Test
    void consume_shouldIgnore_whenEventTypeIsRejected() throws Exception {
        ConsumerRecord<String, String> record = record(
                "{\"eventType\":\"KYC_REJECTED\",\"userId\":\"" + userId + "\"}");

        consumer.consume(record);

        verifyNoInteractions(userRepository);
    }

    @Test
    void consume_shouldThrow_whenPayloadIsMalformed() {
        ConsumerRecord<String, String> record = record("not-valid-json{{{");

        // Malformed JSON propagates — the DLQ error handler catches it at the container level
        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(Exception.class);

        verifyNoInteractions(userRepository);
    }

    @Test
    void consume_shouldWarnAndSkip_whenUserIdMissing() throws Exception {
        ConsumerRecord<String, String> record = record("{\"eventType\":\"KYC_APPROVED\"}");

        consumer.consume(record);

        verifyNoInteractions(userRepository);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("kyc.events", 0, 0L, userId.toString(), value);
    }
}
