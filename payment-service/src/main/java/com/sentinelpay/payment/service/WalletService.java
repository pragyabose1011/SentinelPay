package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.WalletRequest;
import com.sentinelpay.payment.dto.WalletResponse;
import com.sentinelpay.payment.exception.ForbiddenException;
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
 * Handles wallet creation and lifecycle management.
 *
 * <p>Ownership rules:
 * <ul>
 *   <li>A user may only create wallets for themselves.</li>
 *   <li>A user may only view their own wallets.</li>
 *   <li>Only an ADMIN may freeze or close another user's wallet.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository   userRepository;

    @Transactional
    public WalletResponse createWallet(WalletRequest request, UUID actorId) {
        if (!request.getUserId().equals(actorId)) {
            assertAdmin(actorId);
        }
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.getUserId()));

        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Cannot create wallet for a non-ACTIVE user. Status: " + user.getStatus());
        }
        String currency = request.getCurrency().toUpperCase();
        if (walletRepository.findByUserIdAndCurrency(user.getId(), currency).isPresent()) {
            throw new IllegalArgumentException("User already has a " + currency + " wallet.");
        }
        Wallet wallet = Wallet.builder().user(user).currency(currency).build();
        wallet = walletRepository.save(wallet);
        log.info("Wallet created: id={} userId={} currency={}", wallet.getId(), user.getId(), currency);
        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId, UUID actorId) {
        Wallet wallet = findById(walletId);
        assertOwnerOrAdmin(wallet, actorId);
        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> getWalletsByUser(UUID userId, UUID actorId) {
        if (!userId.equals(actorId)) assertAdmin(actorId);
        return walletRepository.findByUserId(userId).stream()
                .map(WalletResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public WalletResponse updateStatus(UUID walletId, Wallet.WalletStatus newStatus, UUID actorId) {
        Wallet wallet = findById(walletId);
        // Users may close their own wallet; only admins can freeze/close others
        if (!wallet.getUser().getId().equals(actorId)) {
            assertAdmin(actorId);
        }
        if (wallet.getStatus() == Wallet.WalletStatus.CLOSED) {
            throw new IllegalArgumentException("Cannot change status of a CLOSED wallet.");
        }
        if (wallet.getStatus() == newStatus) return WalletResponse.from(wallet);
        wallet.setStatus(newStatus);
        wallet = walletRepository.save(wallet);
        log.info("Wallet status updated: walletId={} newStatus={} by actorId={}", walletId, newStatus, actorId);
        return WalletResponse.from(wallet);
    }

    // -------------------------------------------------------------------------
    // Package-level helpers used by PaymentService and DepositWithdrawalService
    // -------------------------------------------------------------------------

    public Wallet findById(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));
    }

    private void assertOwnerOrAdmin(Wallet wallet, UUID actorId) {
        if (wallet.getUser().getId().equals(actorId)) return;
        assertAdmin(actorId);
    }

    private void assertAdmin(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + actorId));
        if (actor.getRole() != User.UserRole.ADMIN) {
            throw new ForbiddenException("Access denied.");
        }
    }
}
