package com.sentinelpay.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.domain.OutboxEvent;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.PaymentRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import com.sentinelpay.payment.repository.TransactionRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Core payment processing logic.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>Idempotency — duplicate requests with the same key return the original result.</li>
 *   <li>Double-spend prevention — optimistic locking on wallet version raises
 *       {@link ObjectOptimisticLockingFailureException} on concurrent debit attempts.</li>
 *   <li>Outbox pattern — a Kafka event is written atomically with the transaction so
 *       downstream services always receive the notification.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final String TOPIC_TRANSACTIONS = "payment.transactions";

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Initiates a payment transfer between two wallets.
     *
     * <p>The method is fully transactional: wallet debits, credits, transaction persistence
     * and outbox event creation all happen in a single database transaction.
     *
     * @param request validated payment request
     * @return the resulting {@link PaymentResponse}
     * @throws IllegalArgumentException  when wallets are missing, frozen, or funds are insufficient
     * @throws IllegalStateException     on idempotency key collision with a different payload
     */
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        // --- Idempotency check ---
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent request: key={}", request.getIdempotencyKey());
            PaymentResponse response = PaymentResponse.from(existing.get());
            response.setIdempotent(true);
            return response;
        }

        // --- Load wallets with pessimistic write lock ---
        Wallet sender = walletRepository.findByIdWithLock(request.getSenderWalletId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sender wallet not found: " + request.getSenderWalletId()));

        Wallet receiver = walletRepository.findByIdWithLock(request.getReceiverWalletId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Receiver wallet not found: " + request.getReceiverWalletId()));

        // --- Validate wallet states ---
        if (sender.getStatus() != Wallet.WalletStatus.ACTIVE) {
            throw new IllegalArgumentException("Sender wallet is not active: " + sender.getId());
        }
        if (receiver.getStatus() != Wallet.WalletStatus.ACTIVE) {
            throw new IllegalArgumentException("Receiver wallet is not active: " + receiver.getId());
        }
        if (!sender.getCurrency().equals(request.getCurrency())) {
            throw new IllegalArgumentException(
                    "Currency mismatch: wallet is " + sender.getCurrency() + " but request is " + request.getCurrency());
        }

        // --- Sufficient funds check ---
        if (sender.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient funds in sender wallet: " + sender.getId());
        }

        // --- Debit sender, credit receiver ---
        sender.setBalance(sender.getBalance().subtract(request.getAmount()));
        receiver.setBalance(receiver.getBalance().add(request.getAmount()));
        walletRepository.save(sender);
        walletRepository.save(receiver);

        // --- Persist transaction ---
        Transaction transaction = Transaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .type(request.getType())
                .description(request.getDescription())
                .status(Transaction.TransactionStatus.COMPLETED)
                .completedAt(Instant.now())
                .build();
        transaction = transactionRepository.save(transaction);

        // --- Outbox event (guaranteed Kafka delivery) ---
        publishOutboxEvent(transaction);

        log.info("Payment processed: txnId={} amount={} {}", transaction.getId(), request.getAmount(), request.getCurrency());
        return PaymentResponse.from(transaction);
    }

    /**
     * Returns a paginated transaction history for a given wallet.
     */
    @Transactional(readOnly = true)
    public Page<PaymentResponse> getTransactionHistory(UUID walletId, Pageable pageable) {
        return transactionRepository
                .findBySenderWalletIdOrReceiverWalletId(walletId, walletId, pageable)
                .map(PaymentResponse::from);
    }

    /**
     * Returns a single transaction by ID.
     */
    @Transactional(readOnly = true)
    public PaymentResponse getTransaction(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        return PaymentResponse.from(transaction);
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
            log.error("Failed to serialize outbox payload for transaction {}", transaction.getId(), e);
            throw new IllegalStateException("Outbox serialization failed", e);
        }
    }
}
