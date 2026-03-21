package com.sentinelpay.payment;

import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.PaymentRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.repository.TransactionRepository;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import com.sentinelpay.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the Payment Service.
 * Uses H2 in-memory DB (PostgreSQL-compatible mode) and mocks Kafka/Redis.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private Wallet senderWallet;
    private Wallet receiverWallet;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        User sender = userRepository.save(User.builder()
                .email("sender@example.com")
                .fullName("Alice Sender")
                .build());

        User receiver = userRepository.save(User.builder()
                .email("receiver@example.com")
                .fullName("Bob Receiver")
                .build());

        senderWallet = walletRepository.save(Wallet.builder()
                .user(sender)
                .currency("USD")
                .balance(new BigDecimal("1000.00"))
                .build());

        receiverWallet = walletRepository.save(Wallet.builder()
                .user(receiver)
                .currency("USD")
                .balance(new BigDecimal("500.00"))
                .build());
    }

    @Test
    void processPayment_shouldDebitSenderAndCreditReceiver() {
        PaymentRequest request = PaymentRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .senderWalletId(senderWallet.getId())
                .receiverWalletId(receiverWallet.getId())
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .type(Transaction.TransactionType.TRANSFER)
                .build();

        PaymentResponse response = paymentService.processPayment(request);

        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(response.getAmount()).isEqualByComparingTo("250.00");
        assertThat(response.isIdempotent()).isFalse();

        Wallet updatedSender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        Wallet updatedReceiver = walletRepository.findById(receiverWallet.getId()).orElseThrow();
        assertThat(updatedSender.getBalance()).isEqualByComparingTo("750.00");
        assertThat(updatedReceiver.getBalance()).isEqualByComparingTo("750.00");
    }

    @Test
    void processPayment_shouldBeIdempotent_whenSameKeyIsSubmittedTwice() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder()
                .idempotencyKey(idempotencyKey)
                .senderWalletId(senderWallet.getId())
                .receiverWalletId(receiverWallet.getId())
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .type(Transaction.TransactionType.TRANSFER)
                .build();

        PaymentResponse first = paymentService.processPayment(request);
        PaymentResponse second = paymentService.processPayment(request);

        assertThat(second.isIdempotent()).isTrue();
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());

        // Wallet balances should only change once
        Wallet updatedSender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertThat(updatedSender.getBalance()).isEqualByComparingTo("900.00");
    }

    @Test
    void processPayment_shouldFail_whenInsufficientFunds() {
        PaymentRequest request = PaymentRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .senderWalletId(senderWallet.getId())
                .receiverWalletId(receiverWallet.getId())
                .amount(new BigDecimal("9999.00"))
                .currency("USD")
                .type(Transaction.TransactionType.TRANSFER)
                .build();

        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void processPayment_shouldFail_whenSenderWalletNotFound() {
        PaymentRequest request = PaymentRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .senderWalletId(UUID.randomUUID())
                .receiverWalletId(receiverWallet.getId())
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .type(Transaction.TransactionType.TRANSFER)
                .build();

        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sender wallet not found");
    }
}
