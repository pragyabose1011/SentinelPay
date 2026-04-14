package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.WalletRequest;
import com.sentinelpay.payment.dto.WalletResponse;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles wallet creation and lifecycle management (freeze, close, reactivate).
 *
 * <p>Rules:
 * <ul>
 *   <li>One wallet per user per currency (enforced by DB unique constraint and validated here).</li>
 *   <li>User must be ACTIVE to create a wallet.</li>
 *   <li>CLOSED wallets cannot transition to any other state.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    /**
     * Creates a new wallet for the given user and currency.
     *
     * @param request validated wallet creation request
     * @return the created wallet
     * @throws IllegalArgumentException if user not found, not ACTIVE, or already has a wallet in that currency
     */
    @Transactional
    public WalletResponse createWallet(WalletRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.getUserId()));

        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Cannot create wallet for a non-ACTIVE user. Status: " + user.getStatus());
        }

        String currency = request.getCurrency().toUpperCase();
        if (walletRepository.findByUserIdAndCurrency(user.getId(), currency).isPresent()) {
            throw new IllegalArgumentException(
                    "User already has a " + currency + " wallet.");
        }

        Wallet wallet = Wallet.builder()
                .user(user)
                .currency(currency)
                .build();
        wallet = walletRepository.save(wallet);
        log.info("Wallet created: id={} userId={} currency={}", wallet.getId(), user.getId(), currency);
        return WalletResponse.from(wallet);
    }

    /**
     * Returns a single wallet by ID.
     *
     * @throws IllegalArgumentException if no wallet exists with the given ID
     */
    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));
        return WalletResponse.from(wallet);
    }

    /**
     * Returns all wallets belonging to a user.
     */
    @Transactional(readOnly = true)
    public List<WalletResponse> getWalletsByUser(UUID userId) {
        return walletRepository.findByUserId(userId)
                .stream()
                .map(WalletResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Updates the status of a wallet.
     *
     * <p>Allowed transitions:
     * <ul>
     *   <li>ACTIVE → FROZEN (freeze wallet, blocks new payments)</li>
     *   <li>ACTIVE → CLOSED (permanent close)</li>
     *   <li>FROZEN → ACTIVE (unfreeze)</li>
     *   <li>FROZEN → CLOSED (close frozen wallet)</li>
     *   <li>CLOSED → any (rejected — terminal state)</li>
     * </ul>
     *
     * @param walletId  target wallet
     * @param newStatus desired status
     * @throws IllegalArgumentException on invalid transitions or unknown wallet
     */
    @Transactional
    public WalletResponse updateStatus(UUID walletId, Wallet.WalletStatus newStatus) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        if (wallet.getStatus() == Wallet.WalletStatus.CLOSED) {
            throw new IllegalArgumentException("Cannot change status of a CLOSED wallet.");
        }
        if (wallet.getStatus() == newStatus) {
            return WalletResponse.from(wallet);
        }

        wallet.setStatus(newStatus);
        wallet = walletRepository.save(wallet);
        log.info("Wallet status updated: walletId={} newStatus={}", walletId, newStatus);
        return WalletResponse.from(wallet);
    }
}
