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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionEventConsumerTest {

    @Mock  NotificationService notificationService;
    @Spy   ObjectMapper        objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks TransactionEventConsumer consumer;

    private final UUID txnId    = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    @Test
    void consume_shouldCallHandleTransactionEvent_withParsedPayload() throws Exception {
        String json = "{\"transactionId\":\"" + txnId + "\","
                + "\"senderWalletId\":\"" + walletId + "\","
                + "\"amount\":10000.00,\"currency\":\"INR\","
                + "\"status\":\"COMPLETED\",\"type\":\"TRANSFER\"}";

        consumer.consume(record(json));

        ArgumentCaptor<com.sentinelpay.notification.dto.TransactionEvent> captor =
                ArgumentCaptor.forClass(com.sentinelpay.notification.dto.TransactionEvent.class);
        verify(notificationService).handleTransactionEvent(captor.capture());
        assertThat(captor.getValue().getTransactionId()).isEqualTo(txnId);
        assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().getCurrency()).isEqualTo("INR");
    }

    @Test
    void consume_shouldThrow_whenJsonIsMalformed() {
        // Exception propagates — DLQ error handler routes it to payment.transactions.DLT
        assertThatThrownBy(() -> consumer.consume(record("not-json")))
                .isInstanceOf(Exception.class);

        verifyNoInteractions(notificationService);
    }

    @Test
    void consume_shouldIgnoreUnknownFields_forwardCompatibility() throws Exception {
        String json = "{\"transactionId\":\"" + txnId + "\","
                + "\"status\":\"COMPLETED\",\"type\":\"TRANSFER\","
                + "\"unknownFutureField\":\"value\"}";

        consumer.consume(record(json));

        verify(notificationService).handleTransactionEvent(any());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("payment.transactions", 0, 0L, txnId.toString(), value);
    }
}
