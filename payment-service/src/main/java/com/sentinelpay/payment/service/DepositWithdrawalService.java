package com.sentinelpay.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.domain.OutboxEvent;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.DepositRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.WithdrawalRequest;
import com.sentinelpay.payment.exception.ForbiddenException;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import com.sentinelpay.payment.repository.TransactionRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles deposits (external money in) and withdrawals (external money out).
 *
 * <p>Neither operation has a counterpart wallet — the sender/receiver wallet FK
 * is null respectively, as allowed by the V2 schema migration.
 *
 * <p>KYC is enforced for withdrawals above the configured threshold.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepositWithdrawalService {

    private static final String TOPIC = "payment.transactions";

    private final WalletRepository      walletRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper          objectMapper;
    private final RazorpayService       razorpayService;

    @Value("${sentinelpay.kyc.transfer-limit:500000}")
    private BigDecimal kycTransferLimit;

    @Value("${sentinelpay.razorpay.enabled:false}")
    private boolean razorpayEnabled;

    // -------------------------------------------------------------------------
    // Deposit
    // -------------------------------------------------------------------------

    /**
     * Credits {@code request.amount} to the target wallet.
     * The acting user must own the wallet.
     */
    @Transactional
    public PaymentResponse deposit(DepositRequest request, UUID actorId) {
        // Idempotency
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            PaymentResponse r = PaymentResponse.from(existing.get());
            r.setIdempotent(true);
            return r;
        }

        Wallet wallet = walletRepository.findByIdWithLock(request.getWalletId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + request.getWalletId()));

        assertOwner(wallet, actorId);

        if (wallet.getStatus() != Wallet.WalletStatus.ACTIVE) {
            throw new IllegalArgumentException("Cannot deposit into a non-ACTIVE wallet.");
        }

        if (razorpayEnabled) {
            // Gateway flow: create Razorpay order → return PENDING, wallet credited on webhook
            String orderId = razorpayService.createOrder(
                    request.getAmount(), wallet.getCurrency(), request.getIdempotencyKey());

            Transaction txn = Transaction.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .receiverWallet(wallet)
                    .amount(request.getAmount())
                    .currency(wallet.getCurrency())
                    .type(Transaction.TransactionType.DEPOSIT)
                    .description(request.getReference() != null ? "Deposit: " + request.getReference() : "Deposit")
                    .gatewayReference(orderId)
                    .status(Transaction.TransactionStatus.PENDING)
                    .build();
            txn = transactionRepository.save(txn);
            log.info("Deposit pending Razorpay confirmation: walletId={} orderId={}", wallet.getId(), orderId);
            return PaymentResponse.from(txn);
        }

        // Direct flow (dev / Razorpay disabled): immediately credit wallet
        wallet.setBalance(wallet.getBalance().add(request.getAmount()));
        walletRepository.save(wallet);

        Transaction txn = Transaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .receiverWallet(wallet)
                .amount(request.getAmount())
                .currency(wallet.getCurrency())
                .type(Transaction.TransactionType.DEPOSIT)
                .description(request.getReference() != null ? "Deposit: " + request.getReference() : "Deposit")
                .status(Transaction.TransactionStatus.COMPLETED)
                .completedAt(Instant.now())
                .build();
        txn = transactionRepository.save(txn);
        publishOutboxEvent(txn);

        log.info("Deposit completed (direct): walletId={} amount={} {}", wallet.getId(), request.getAmount(), wallet.getCurrency());
        return PaymentResponse.from(txn);
    }

    // -------------------------------------------------------------------------
    // Razorpay webhook — payment.captured event
    // -------------------------------------------------------------------------

    /**
     * Handles a verified {@code payment.captured} event from Razorpay.
     *
     * <p>Finds the pending DEPOSIT transaction by the Razorpay order ID,
     * credits the receiver wallet, marks the transaction COMPLETED,
     * and publishes the outbox event. Duplicate webhooks are safely ignored.
     */
    @Transactional
    public void handleRazorpayWebhook(String rawPayload) {
        JsonNode event;
        try {
            event = objectMapper.readTree(rawPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Razorpay webhook payload", e);
        }

        String eventType = event.path("event").asText();
        if (!"payment.captured".equals(eventType)) {
            log.debug("Ignoring Razorpay event type: {}", eventType);
            return;
        }

        String orderId = event.at("/payload/payment/entity/order_id").asText(null);
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Razorpay webhook missing order_id");
        }

        Transaction txn = transactionRepository.findByGatewayReference(orderId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pending deposit found for Razorpay order: " + orderId));

        if (txn.getStatus() != Transaction.TransactionStatus.PENDING) {
            log.info("Duplicate Razorpay webhook ignored: orderId={} txnId={} status={}",
                    orderId, txn.getId(), txn.getStatus());
            return;
        }

        UUID txnId = txn.getId();
        Wallet wallet = walletRepository.findByIdWithLock(txn.getReceiverWallet().getId())
                .orElseThrow(() -> new IllegalStateException("Receiver wallet not found for txn: " + txnId));
        wallet.setBalance(wallet.getBalance().add(txn.getAmount()));
        walletRepository.save(wallet);

        txn.setStatus(Transaction.TransactionStatus.COMPLETED);
        txn.setCompletedAt(Instant.now());
        txn = transactionRepository.save(txn);
        publishOutboxEvent(txn);

        log.info("Deposit completed via Razorpay webhook: orderId={} txnId={} walletId={}",
                orderId, txn.getId(), wallet.getId());
    }

    // -------------------------------------------------------------------------
    // Withdrawal
    // -------------------------------------------------------------------------

    /**
     * Debits {@code request.amount} from the source wallet.
     * KYC is required for withdrawals above the threshold.
     */
    @Transactional
    public PaymentResponse withdraw(WithdrawalRequest request, UUID actorId) {
        // Idempotency
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            PaymentResponse r = PaymentResponse.from(existing.get());
            r.setIdempotent(true);
            return r;
        }

        Wallet wallet = walletRepository.findByIdWithLock(request.getWalletId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + request.getWalletId()));

        assertOwner(wallet, actorId);

        if (wallet.getStatus() != Wallet.WalletStatus.ACTIVE) {
            throw new IllegalArgumentException("Cannot withdraw from a non-ACTIVE wallet.");
        }
        if (request.getAmount().compareTo(kycTransferLimit) > 0
                && !wallet.getUser().isKycVerified()) {
            throw new IllegalArgumentException(
                    "KYC verification required for withdrawals above " + kycTransferLimit + ".");
        }
        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient funds in wallet: " + wallet.getId());
        }

        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        Transaction txn = Transaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .senderWallet(wallet)
                .amount(request.getAmount())
                .currency(wallet.getCurrency())
                .type(Transaction.TransactionType.WITHDRAWAL)
                .description(request.getDestination() != null
                        ? "Withdrawal to: " + request.getDestination() : "Withdrawal")
                .status(Transaction.TransactionStatus.COMPLETED)
                .completedAt(Instant.now())
                .build();
        txn = transactionRepository.save(txn);
        publishOutboxEvent(txn);

        log.info("Withdrawal: walletId={} amount={} {}", wallet.getId(), request.getAmount(), wallet.getCurrency());
        return PaymentResponse.from(txn);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertOwner(Wallet wallet, UUID actorId) {
        if (!wallet.getUser().getId().equals(actorId)) {
            throw new ForbiddenException("You do not own this wallet.");
        }
    }

    private void publishOutboxEvent(Transaction txn) {
        try {
            String payload = objectMapper.writeValueAsString(PaymentResponse.from(txn));
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType("Transaction")
                    .aggregateId(txn.getId().toString())
                    .topic(TOPIC)
                    .eventType("TRANSACTION_" + txn.getStatus().name())
                    .payload(payload)
                    .build();
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox serialization failed", e);
        }
    }
}
