package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.UserResponse;
import com.sentinelpay.payment.dto.WalletResponse;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import com.sentinelpay.payment.repository.TransactionRepository;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.service.AuditLogService;
import com.sentinelpay.payment.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Slice test for {@link AdminController}.
 *
 * <p>All endpoints require ROLE_ADMIN. Tests verify:
 * - Admins get 200/204 on valid requests
 * - Regular users get 403
 * - Unauthenticated requests get 401
 */
@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JwtTokenProvider      jwtTokenProvider;
    @MockBean RedisTemplate<String, String> redisTemplate;
    @MockBean UserDetailsService    userDetailsService;
    @MockBean TransactionRepository transactionRepository;
    @MockBean UserRepository        userRepository;
    @MockBean WalletRepository      walletRepository;
    @MockBean OutboxEventRepository outboxEventRepository;
    @MockBean AuditLogService       auditLogService;
    @MockBean WalletService         walletService;

    private static final String ADMIN_TOKEN = "Bearer admin-token";
    private static final String USER_TOKEN  = "Bearer user-token";

    private final UUID adminId = UUID.randomUUID();
    private final UUID userId  = UUID.randomUUID();
    private final UUID txnId   = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    @BeforeEach
    void setUpJwt() {
        when(jwtTokenProvider.validate("admin-token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("admin-token")).thenReturn(adminId);
        when(jwtTokenProvider.extractRole("admin-token")).thenReturn("ADMIN");

        when(jwtTokenProvider.validate("user-token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("user-token")).thenReturn(userId);
        when(jwtTokenProvider.extractRole("user-token")).thenReturn("USER");
    }

    // =========================================================================
    // GET /api/v1/admin/transactions
    // =========================================================================

    @Test
    void listTransactions_shouldReturn200_whenAdmin() throws Exception {
        when(transactionRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/v1/admin/transactions")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listTransactions_shouldReturn403_whenRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/admin/transactions")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void listTransactions_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/transactions"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET /api/v1/admin/users
    // =========================================================================

    @Test
    void listUsers_shouldReturn200_whenAdmin() throws Exception {
        when(userRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void listUsers_shouldReturn403_whenRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // PATCH /api/v1/admin/transactions/{id}/unflag
    // =========================================================================

    @Test
    void unflagTransaction_shouldReturn200_whenAdmin() throws Exception {
        Transaction txn = buildTransaction();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenReturn(txn);

        mockMvc.perform(patch("/api/v1/admin/transactions/{id}/unflag", txnId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void unflagTransaction_shouldReturn400_whenNotFound() throws Exception {
        when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/admin/transactions/{id}/unflag", txnId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unflagTransaction_shouldReturn403_whenRegularUser() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/transactions/{id}/unflag", txnId)
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // GET /api/v1/admin/outbox/stats
    // =========================================================================

    @Test
    void getOutboxStats_shouldReturn200_whenAdmin() throws Exception {
        when(outboxEventRepository.countByProcessedFalse()).thenReturn(3L);
        when(outboxEventRepository.countByProcessedTrue()).thenReturn(42L);

        mockMvc.perform(get("/api/v1/admin/outbox/stats")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingEvents").value(3))
                .andExpect(jsonPath("$.processedEvents").value(42));
    }

    // =========================================================================
    // PATCH /api/v1/admin/wallets/{id}/status
    // =========================================================================

    @Test
    void adminUpdateWalletStatus_shouldReturn200_whenAdmin() throws Exception {
        WalletResponse frozen = WalletResponse.builder()
                .id(walletId).userId(userId).currency("INR")
                .balance(BigDecimal.ZERO).status(Wallet.WalletStatus.FROZEN)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(walletService.updateStatus(any(), any(), any())).thenReturn(frozen);

        mockMvc.perform(patch("/api/v1/admin/wallets/{id}/status", walletId)
                        .header("Authorization", ADMIN_TOKEN)
                        .param("status", "FROZEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Transaction buildTransaction() {
        User user = User.builder().id(userId).email("alice@example.com").fullName("Alice").build();
        Wallet wallet = Wallet.builder().id(walletId).user(user).currency("INR")
                .balance(new BigDecimal("100000.00")).build();
        return Transaction.builder()
                .id(txnId)
                .senderWallet(wallet)
                .receiverWallet(wallet)
                .amount(new BigDecimal("10000.00"))
                .currency("INR")
                .status(Transaction.TransactionStatus.COMPLETED)
                .type(Transaction.TransactionType.TRANSFER)
                .createdAt(Instant.now())
                .build();
    }
}
