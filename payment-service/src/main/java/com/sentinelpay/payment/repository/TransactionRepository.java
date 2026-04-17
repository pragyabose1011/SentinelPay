package com.sentinelpay.payment.repository;

import com.sentinelpay.payment.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByGatewayReference(String gatewayReference);

    Page<Transaction> findBySenderWalletId(UUID senderWalletId, Pageable pageable);

    Page<Transaction> findByReceiverWalletId(UUID receiverWalletId, Pageable pageable);

    Page<Transaction> findBySenderWalletIdOrReceiverWalletId(
            UUID senderWalletId, UUID receiverWalletId, Pageable pageable);

    /**
     * Velocity check for fraud scoring — counts how many transactions
     * the sender wallet has initiated after the given timestamp.
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.senderWallet.id = :walletId AND t.createdAt > :after")
    long countBySenderWalletIdAndCreatedAtAfter(
            @Param("walletId") UUID walletId,
            @Param("after") Instant after);

    /**
     * Guards against double-reversals — returns true if a REVERSAL already
     * exists for the given original transaction.
     */
    boolean existsByReferenceTransactionIdAndType(
            UUID referenceTransactionId, Transaction.TransactionType type);

    /**
     * Cursor-based (keyset) pagination for transaction history.
     * Returns transactions older than the cursor, ordered newest-first.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE (t.senderWallet.id = :walletId OR t.receiverWallet.id = :walletId)
              AND (CAST(:cursorCreatedAt AS java.time.Instant) IS NULL
                   OR t.createdAt < :cursorCreatedAt
                   OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId))
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    Page<Transaction> findByWalletIdCursor(
            @Param("walletId") UUID walletId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
