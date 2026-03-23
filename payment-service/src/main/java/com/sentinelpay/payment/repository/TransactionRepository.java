package com.sentinelpay.payment.repository;

import com.sentinelpay.payment.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Page<Transaction> findBySenderWalletId(UUID senderWalletId, Pageable pageable);

    Page<Transaction> findByReceiverWalletId(UUID receiverWalletId, Pageable pageable);

    Page<Transaction> findBySenderWalletIdOrReceiverWalletId(
            UUID senderWalletId, UUID receiverWalletId, Pageable pageable);
}
