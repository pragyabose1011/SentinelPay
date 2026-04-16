package com.sentinelpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.consumer.KycEventConsumer;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.*;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import com.sentinelpay.payment.repository.TransactionRepository;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import com.sentinelpay.payment.service.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Full integration tests against a real PostgreSQL database (via Testcontainers).
 * Kafka and Redis are mocked — the DB is the correctness boundary.
 *
 * All monetary amounts are in INR (Indian Rupees).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // --- Mocked infrastructure ---
    @MockBean KafkaTemplate<String, String>            kafkaTemplate;
    @MockBean(name = "redisTemplate")
    RedisTemplate<String, String>                      redisTemplate;
    @MockBean RateLimiterService                       rateLimiterService;   // no-op by default
    @MockBean DistributedLockService                   distributedLockService;

    // --- Real services under test ---
    @Autowired PaymentService            paymentService;
    @Autowired DepositWithdrawalService  depositWithdrawalService;
    @Autowired KycEventConsumer          kycEventConsumer;
    @Autowired ObjectMapper              objectMapper;
    @Autowired UserRepository            userRepository;
    @Autowired WalletRepository          walletRepository;
    @Autowired TransactionRepository     transactionRepository;
    @Autowired OutboxEventRepository     outboxEventRepository;

    private User   alice;
    private User   bob;
    private Wallet aliceINR;
    private Wallet bobINR;
    private Wallet aliceEUR;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").fullName("Alice").kycVerified(true).build());
        bob   = userRepository.save(User.builder()
                .email("bob@example.com").fullName("Bob").build());

        // Alice: ₹10,00,000 (10 lakhs)
        aliceINR = walletRepository.save(Wallet.builder()
                .user(alice).currency("INR").balance(new BigDecimal("1000000.00")).build());
        // Bob: ₹50,000
        bobINR = walletRepository.save(Wallet.builder()
                .user(bob).currency("INR").balance(new BigDecimal("50000.00")).build());
        aliceEUR = walletRepository.save(Wallet.builder()
                .user(alice).currency("EUR").balance(new BigDecimal("500000.00")).build());

        // Distributed lock returns a valid token by default
        when(distributedLockService.tryLock(anyString(), anyLong(), any())).thenReturn("test-token");
    }

    // =========================================================================
    // Happy-path transfer
    // =========================================================================

    @Test
    void transfer_shouldDebitSenderAndCreditReceiver() {
        PaymentResponse response = paymentService.processPayment(buildTransfer("25000.00"));

        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(response.getAmount()).isEqualByComparingTo("25000.00");
        assertThat(response.isIdempotent()).isFalse();
        assertThat(walletRepository.findById(aliceINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("975000.00");
        assertThat(walletRepository.findById(bobINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("75000.00");
    }

    // =========================================================================
    // Idempotency
    // =========================================================================

    @Test
    void transfer_shouldBeIdempotent_whenSameKeySubmittedTwice() {
        String key = UUID.randomUUID().toString();
        PaymentResponse first  = paymentService.processPayment(buildTransfer("10000.00", key));
        PaymentResponse second = paymentService.processPayment(buildTransfer("10000.00", key));

        assertThat(second.isIdempotent()).isTrue();
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
        // Balance deducted exactly once
        assertThat(walletRepository.findById(aliceINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("990000.00");
    }

    // =========================================================================
    // Validation failures
    // =========================================================================

    @Test
    void transfer_shouldFail_whenInsufficientFunds() {
        assertThatThrownBy(() -> paymentService.processPayment(buildTransfer("9999999.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void transfer_shouldFail_whenSenderEqualsReceiver() {
        PaymentRequest req = PaymentRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .senderWalletId(aliceINR.getId())
                .receiverWalletId(aliceINR.getId())
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .type(Transaction.TransactionType.TRANSFER)
                .build();
        assertThatThrownBy(() -> paymentService.processPayment(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");
    }

    @Test
    void transfer_shouldFail_whenWalletNotFound() {
        PaymentRequest req = PaymentRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .senderWalletId(UUID.randomUUID())
                .receiverWalletId(bobINR.getId())
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .type(Transaction.TransactionType.TRANSFER)
                .build();
        assertThatThrownBy(() -> paymentService.processPayment(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sender wallet not found");
    }

    // =========================================================================
    // KYC enforcement
    // =========================================================================

    @Test
    void transfer_shouldFail_whenAboveKycLimit_andNotKycVerified() {
        // Bob is NOT kyc-verified; try to send > ₹5,00,000 (KYC limit)
        // First seed Bob with enough balance
        bobINR.setBalance(new BigDecimal("1000000.00"));
        walletRepository.save(bobINR);

        PaymentRequest req = PaymentRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .senderWalletId(bobINR.getId())
                .receiverWalletId(aliceINR.getId())
                .amount(new BigDecimal("600000.00"))   // ₹6,00,000 — above ₹5,00,000 KYC limit
                .currency("INR")
                .type(Transaction.TransactionType.TRANSFER)
                .build();
        assertThatThrownBy(() -> paymentService.processPayment(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KYC");
    }

    @Test
    void transfer_shouldSucceed_whenAboveKycLimit_andKycVerified() {
        // Alice IS kyc-verified; ₹6,00,000 is above limit but KYC passes
        PaymentResponse response = paymentService.processPayment(buildTransfer("600000.00"));
        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
    }

    // =========================================================================
    // Fraud scoring
    // =========================================================================

    @Test
    void transfer_shouldBlockHighFraudRisk_onVeryLargeAmount() {
        // Amount > ₹10,00,000 triggers HIGH fraud score (40 pts → blocked at 60+)
        // Seed Alice with enough balance to isolate the fraud check
        aliceINR.setBalance(new BigDecimal("50000000.00"));  // ₹5 crore
        walletRepository.save(aliceINR);

        PaymentRequest req = buildTransfer("1000001.00");   // ₹10,00,001 — just above threshold
        assertThatThrownBy(() -> paymentService.processPayment(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraud risk");
    }

    @Test
    void transfer_shouldFlagMediumRisk_andStoreInMetadata() {
        // Make Alice a new user (< 7 days) + send > ₹1,00,000 → MEDIUM risk (30 pts)
        alice.setCreatedAt(java.time.Instant.now());
        userRepository.save(alice);

        PaymentResponse response = paymentService.processPayment(buildTransfer("150000.00")); // ₹1,50,000
        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(response.getFraudRiskLevel()).isEqualTo("MEDIUM");
    }

    // =========================================================================
    // Reversal
    // =========================================================================

    @Test
    void reversal_shouldSwapBalancesAndMarkOriginalReversed() {
        PaymentResponse original = paymentService.processPayment(buildTransfer("30000.00")); // ₹30,000

        ReversalRequest reversalReq = new ReversalRequest();
        reversalReq.setIdempotencyKey(UUID.randomUUID().toString());
        reversalReq.setReason("customer request");

        PaymentResponse reversal = paymentService.reverseTransaction(original.getTransactionId(), reversalReq);

        assertThat(reversal.getType()).isEqualTo(Transaction.TransactionType.REVERSAL);
        assertThat(reversal.getReferenceTransactionId()).isEqualTo(original.getTransactionId());
        // Balances restored
        assertThat(walletRepository.findById(aliceINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000000.00");
        assertThat(walletRepository.findById(bobINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("50000.00");
        // Original marked REVERSED
        assertThat(transactionRepository.findById(original.getTransactionId()).orElseThrow().getStatus())
                .isEqualTo(Transaction.TransactionStatus.REVERSED);
    }

    @Test
    void reversal_shouldFail_whenAlreadyReversed() {
        PaymentResponse original = paymentService.processPayment(buildTransfer("10000.00"));
        ReversalRequest req = new ReversalRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        paymentService.reverseTransaction(original.getTransactionId(), req);

        ReversalRequest req2 = new ReversalRequest();
        req2.setIdempotencyKey(UUID.randomUUID().toString());
        assertThatThrownBy(() -> paymentService.reverseTransaction(original.getTransactionId(), req2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been reversed");
    }

    // =========================================================================
    // Deposit / Withdrawal
    // =========================================================================

    @Test
    void deposit_shouldCreditWallet() {
        DepositRequest req = new DepositRequest();
        req.setWalletId(bobINR.getId());
        req.setAmount(new BigDecimal("100000.00"));  // ₹1,00,000
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setReference("BANK-REF-001");

        PaymentResponse response = depositWithdrawalService.deposit(req, bob.getId());

        assertThat(response.getType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(walletRepository.findById(bobINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("150000.00");
    }

    @Test
    void withdrawal_shouldDebitWallet() {
        WithdrawalRequest req = new WithdrawalRequest();
        req.setWalletId(aliceINR.getId());
        req.setAmount(new BigDecimal("50000.00"));  // ₹50,000
        req.setIdempotencyKey(UUID.randomUUID().toString());

        PaymentResponse response = depositWithdrawalService.withdraw(req, alice.getId());

        assertThat(response.getType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(walletRepository.findById(aliceINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("950000.00");
    }

    @Test
    void withdrawal_shouldFail_whenInsufficientFunds() {
        WithdrawalRequest req = new WithdrawalRequest();
        req.setWalletId(bobINR.getId());
        // ₹4,99,999 is under ₹5,00,000 KYC threshold so KYC check passes,
        // but Bob only has ₹50,000 so the balance check fires.
        req.setAmount(new BigDecimal("499999.00"));
        req.setIdempotencyKey(UUID.randomUUID().toString());

        assertThatThrownBy(() -> depositWithdrawalService.withdraw(req, bob.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    // =========================================================================
    // Double-spend race condition
    // =========================================================================

    @Test
    void transfer_shouldPreventDoubleSpend_underConcurrency() throws InterruptedException {
        // Alice has ₹20,000; fire 5 concurrent ₹15,000 transfers — only 1 should succeed
        aliceINR.setBalance(new BigDecimal("20000.00"));
        walletRepository.save(aliceINR);

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures  = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    paymentService.processPayment(buildTransfer("15000.00",
                            "concurrent-key-" + idx));
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(4);
        assertThat(walletRepository.findById(aliceINR.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("5000.00");
    }

    // =========================================================================
    // Outbox events
    // =========================================================================

    @Test
    void transfer_shouldWriteOutboxEvent() {
        long before = outboxEventRepository.count();
        paymentService.processPayment(buildTransfer("5000.00"));
        assertThat(outboxEventRepository.count()).isEqualTo(before + 1);
    }

    // =========================================================================
    // KYC event consumer — end-to-end Kafka → DB flow
    // =========================================================================

    @Test
    void kycEventConsumer_shouldSetKycVerified_whenKycApprovedEventConsumed() throws Exception {
        // bob starts un-verified
        assertThat(userRepository.findById(bob.getId()).orElseThrow().isKycVerified()).isFalse();

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "eventType", "KYC_APPROVED",
                "userId",    bob.getId().toString(),
                "userEmail", "bob@example.com",
                "fullName",  "Bob"
        ));
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("kyc.events", 0, 0L, bob.getId().toString(), payload);

        kycEventConsumer.consume(record);

        assertThat(userRepository.findById(bob.getId()).orElseThrow().isKycVerified()).isTrue();
    }

    @Test
    void kycEventConsumer_shouldIgnore_whenEventTypeIsNotApproved() throws Exception {
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "eventType", "KYC_SUBMITTED",
                "userId",    bob.getId().toString()
        ));
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("kyc.events", 0, 1L, bob.getId().toString(), payload);

        kycEventConsumer.consume(record);

        assertThat(userRepository.findById(bob.getId()).orElseThrow().isKycVerified()).isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PaymentRequest buildTransfer(String amount) {
        return buildTransfer(amount, UUID.randomUUID().toString());
    }

    private PaymentRequest buildTransfer(String amount, String idempotencyKey) {
        return PaymentRequest.builder()
                .idempotencyKey(idempotencyKey)
                .senderWalletId(aliceINR.getId())
                .receiverWalletId(bobINR.getId())
                .amount(new BigDecimal(amount))
                .currency("INR")
                .type(Transaction.TransactionType.TRANSFER)
                .build();
    }
}
