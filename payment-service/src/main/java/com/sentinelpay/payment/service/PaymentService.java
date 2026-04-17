package com.sentinelpay.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.domain.OutboxEvent;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.PaymentRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.ReversalRequest;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import com.sentinelpay.payment.repository.TransactionRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Core payment processing logic.
 *
 * <p>Per-payment guarantees (in order of execution):
 * <ol>
 *   <li>Idempotency — Redis cache first, DB fallback; duplicate keys return the original result.</li>
 *   <li>Rate limiting — max 20 payments/min per sender wallet (Redis sliding window).</li>
 *   <li>Distributed lock — serialises concurrent requests for the same sender wallet.</li>
 *   <li>KYC enforcement — transfers above {@code sentinelpay.kyc.transfer-limit} require KYC.</li>
 *   <li>Fraud scoring — HIGH-risk payments blocked; MEDIUM flagged in metadata.</li>
 *   <li>Multi-currency — cross-currency transfers auto-converted; rate stored in metadata.</li>
 *   <li>Double-spend prevention — DB pessimistic write lock on wallet rows.</li>
 *   <li>Outbox pattern — Kafka event written atomically with the transaction.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final String TOPIC_TRANSACTIONS  = "payment.transactions";
    private static final String IDEMPOTENCY_PREFIX  = "idem:";
    private static final Duration IDEMPOTENCY_TTL   = Duration.ofHours(24);
    private static final long LOCK_TTL_SECONDS      = 10L;

    private final WalletRepository        walletRepository;
    private final TransactionRepository   transactionRepository;
    private final OutboxEventRepository   outboxEventRepository;
    private final ObjectMapper            objectMapper;
    private final RateLimiterService      rateLimiterService;
    private final DistributedLockService  distributedLockService;
    private final FraudScoringService     fraudScoringService;
    private final ExchangeRateService     exchangeRateService;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry           meterRegistry;

    @Value("${sentinelpay.kyc.transfer-limit:500000}")
    private BigDecimal kycTransferLimit;

    @Value("${sentinelpay.fraud.enabled:true}")
    private boolean fraudEnabled;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Initiates a transfer between two wallets (same or different currencies).
     */
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        // 1. Redis idempotency cache (fast path)
        PaymentResponse cached = getCachedIdempotentResponse(request.getIdempotencyKey());
        if (cached != null) {
            cached.setIdempotent(true);
            return cached;
        }

        // 2. DB idempotency fallback
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent (DB): key={}", request.getIdempotencyKey());
            PaymentResponse response = PaymentResponse.from(existing.get());
            response.setIdempotent(true);
            cacheIdempotentResponse(request.getIdempotencyKey(), response);
            return response;
        }

        // 3. Sender != receiver guard
        if (request.getSenderWalletId().equals(request.getReceiverWalletId())) {
            throw new IllegalArgumentException("Sender and receiver wallet must be different.");
        }

        // 4. Rate limit
        rateLimiterService.checkPaymentRateLimit(request.getSenderWalletId().toString());

        // 5. Distributed lock on sender wallet
        String lockToken = distributedLockService.tryLock(
                request.getSenderWalletId().toString(), LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (lockToken == null) {
            throw new IllegalStateException(
                    "A payment is already being processed for this wallet — please retry shortly.");
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            PaymentResponse response = doProcessPayment(request);
            cacheIdempotentResponse(request.getIdempotencyKey(), response);
            meterRegistry.counter("sentinelpay.payments.total",
                    "type", request.getType().name(), "outcome", "success").increment();
            return response;
        } catch (Exception e) {
            outcome = "error";
            meterRegistry.counter("sentinelpay.payments.total",
                    "type", request.getType() != null ? request.getType().name() : "UNKNOWN",
                    "outcome", "error").increment();
            throw e;
        } finally {
            sample.stop(Timer.builder("sentinelpay.payment.duration")
                    .tag("type", request.getType() != null ? request.getType().name() : "UNKNOWN")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
            distributedLockService.unlock(request.getSenderWalletId().toString(), lockToken);
        }
    }

    /**
     * Reverses a completed transaction: credits the original sender, debits the original receiver.
     */
    @Transactional
    public PaymentResponse reverseTransaction(UUID transactionId, ReversalRequest request) {
        // Idempotency
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            PaymentResponse response = PaymentResponse.from(existing.get());
            response.setIdempotent(true);
            return response;
        }

        Transaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        if (transactionRepository.existsByReferenceTransactionIdAndType(
                transactionId, Transaction.TransactionType.REVERSAL)) {
            throw new IllegalArgumentException("Transaction " + transactionId + " has already been reversed.");
        }
        if (original.getType() == Transaction.TransactionType.REVERSAL) {
            throw new IllegalArgumentException("A reversal cannot itself be reversed.");
        }
        if (original.getStatus() != Transaction.TransactionStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "Only COMPLETED transactions can be reversed. Status: " + original.getStatus());
        }

        Wallet origReceiver = walletRepository.findByIdWithLock(original.getReceiverWallet().getId())
                .orElseThrow(() -> new IllegalArgumentException("Receiver wallet not found."));
        Wallet origSender   = walletRepository.findByIdWithLock(original.getSenderWallet().getId())
                .orElseThrow(() -> new IllegalArgumentException("Sender wallet not found."));

        if (origReceiver.getBalance().compareTo(original.getAmount()) < 0) {
            throw new IllegalArgumentException("Receiver wallet has insufficient funds for reversal.");
        }

        origReceiver.setBalance(origReceiver.getBalance().subtract(original.getAmount()));
        origSender.setBalance(origSender.getBalance().add(original.getAmount()));
        walletRepository.save(origReceiver);
        walletRepository.save(origSender);

        original.setStatus(Transaction.TransactionStatus.REVERSED);
        transactionRepository.save(original);

        String description = request.getReason() != null
                ? "Reversal: " + request.getReason()
                : "Reversal of transaction " + transactionId;

        Transaction reversal = Transaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .senderWallet(origReceiver)
                .receiverWallet(origSender)
                .amount(original.getAmount())
                .currency(original.getCurrency())
                .type(Transaction.TransactionType.REVERSAL)
                .description(description)
                .status(Transaction.TransactionStatus.COMPLETED)
                .referenceTransactionId(transactionId)
                .completedAt(Instant.now())
                .build();
        reversal = transactionRepository.save(reversal);
        publishOutboxEvent(reversal);

        meterRegistry.counter("sentinelpay.payments.total", "type", "REVERSAL", "outcome", "success").increment();
        log.info("Reversal completed: reversalId={} originalId={}", reversal.getId(), transactionId);
        return PaymentResponse.from(reversal);
    }

    /**
     * Cursor-based paginated transaction history for a wallet.
     * Pass {@code cursorCreatedAt} and {@code cursorId} from the previous page's last item.
     */
    @Transactional(readOnly = true)
    public Page<PaymentResponse> getTransactionHistory(UUID walletId,
                                                        Instant cursorCreatedAt,
                                                        UUID cursorId,
                                                        Pageable pageable) {
        return transactionRepository
                .findByWalletIdCursor(walletId, cursorCreatedAt, cursorId, pageable)
                .map(PaymentResponse::from);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getTransaction(UUID transactionId) {
        return PaymentResponse.from(
                transactionRepository.findById(transactionId)
                        .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId)));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private PaymentResponse doProcessPayment(PaymentRequest request) {
        // Load wallets with pessimistic write lock
        Wallet sender = walletRepository.findByIdWithLock(request.getSenderWalletId())
                .orElseThrow(() -> new IllegalArgumentException("Sender wallet not found: " + request.getSenderWalletId()));
        Wallet receiver = walletRepository.findByIdWithLock(request.getReceiverWalletId())
                .orElseThrow(() -> new IllegalArgumentException("Receiver wallet not found: " + request.getReceiverWalletId()));

        // Validate wallet states
        if (sender.getStatus()   != Wallet.WalletStatus.ACTIVE)
            throw new IllegalArgumentException("Sender wallet is not active: "   + sender.getId());
        if (receiver.getStatus() != Wallet.WalletStatus.ACTIVE)
            throw new IllegalArgumentException("Receiver wallet is not active: " + receiver.getId());

        // KYC enforcement
        if (request.getAmount().compareTo(kycTransferLimit) > 0
                && !sender.getUser().isKycVerified()) {
            throw new IllegalArgumentException(
                    "KYC verification required for transfers above " + kycTransferLimit
                            + " " + sender.getCurrency() + ".");
        }

        // Multi-currency: sender currency is the payment currency; convert for receiver if different
        String senderCurrency   = sender.getCurrency();
        String receiverCurrency = receiver.getCurrency();
        BigDecimal debitAmount  = request.getAmount();
        BigDecimal creditAmount;
        String exchangeRateJson = null;

        if (!senderCurrency.equals(receiverCurrency)) {
            BigDecimal rate = exchangeRateService.getRate(senderCurrency, receiverCurrency);
            creditAmount    = exchangeRateService.convert(debitAmount, senderCurrency, receiverCurrency);
            exchangeRateJson = String.format(
                    ",\"exchange_rate\":{\"from\":\"%s\",\"to\":\"%s\",\"rate\":%s,\"credit_amount\":%s}",
                    senderCurrency, receiverCurrency, rate.toPlainString(), creditAmount.toPlainString());
        } else {
            if (!senderCurrency.equals(request.getCurrency())) {
                throw new IllegalArgumentException(
                        "Currency mismatch: wallet is " + senderCurrency + " but request is " + request.getCurrency());
            }
            creditAmount = debitAmount;
        }

        // Sufficient funds
        if (sender.getBalance().compareTo(debitAmount) < 0) {
            throw new IllegalArgumentException("Insufficient funds in sender wallet: " + sender.getId());
        }

        // Fraud scoring
        FraudScoringService.FraudResult fraud = fraudEnabled
                ? fraudScoringService.score(sender, debitAmount)
                : new FraudScoringService.FraudResult(0, FraudScoringService.RiskLevel.LOW, "disabled");

        if (fraud.isBlocked()) {
            log.warn("Payment blocked: walletId={} score={}", sender.getId(), fraud.score());
            throw new IllegalArgumentException("Payment declined: high fraud risk detected. Contact support.");
        }
        if (fraud.level() == FraudScoringService.RiskLevel.MEDIUM) {
            log.warn("Medium fraud risk: walletId={} score={}", sender.getId(), fraud.score());
        }

        // Debit / credit
        sender.setBalance(sender.getBalance().subtract(debitAmount));
        receiver.setBalance(receiver.getBalance().add(creditAmount));
        walletRepository.save(sender);
        walletRepository.save(receiver);

        // Build metadata JSON
        String metadata = "{" + fraud.toJson().substring(1, fraud.toJson().length() - 1)
                + (exchangeRateJson != null ? exchangeRateJson : "") + "}";

        Transaction transaction = Transaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(debitAmount)
                .currency(senderCurrency)
                .type(request.getType())
                .description(request.getDescription())
                .status(Transaction.TransactionStatus.COMPLETED)
                .metadata(metadata)
                .completedAt(Instant.now())
                .build();
        transaction = transactionRepository.save(transaction);
        publishOutboxEvent(transaction);

        log.info("Payment processed: txnId={} amount={} {} fraud={}",
                transaction.getId(), debitAmount, senderCurrency, fraud.score());

        PaymentResponse response = PaymentResponse.from(transaction);
        response.setFraudScore(fraud.score());
        response.setFraudRiskLevel(fraud.level().name());
        return response;
    }

    private void publishOutboxEvent(Transaction transaction) {
        try {
            String payload = objectMapper.writeValueAsString(PaymentResponse.from(transaction));
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType("Transaction")
                    .aggregateId(transaction.getId().toString())
                    .topic(TOPIC_TRANSACTIONS)
                    .eventType("TRANSACTION_" + transaction.getStatus().name())
                    .payload(payload)
                    .build();
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            log.error("Outbox serialization failed for txn {}", transaction.getId(), e);
            throw new IllegalStateException("Outbox serialization failed", e);
        }
    }

    private PaymentResponse getCachedIdempotentResponse(String key) {
        try {
            String json = redisTemplate.opsForValue().get(IDEMPOTENCY_PREFIX + key);
            if (json != null) {
                log.info("Idempotent (Redis): key={}", key);
                return objectMapper.readValue(json, PaymentResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis idempotency cache read failed: {}", e.getMessage());
        }
        return null;
    }

    private void cacheIdempotentResponse(String key, PaymentResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(IDEMPOTENCY_PREFIX + key, json, IDEMPOTENCY_TTL);
        } catch (Exception e) {
            log.warn("Redis idempotency cache write failed: {}", e.getMessage());
        }
    }
}
