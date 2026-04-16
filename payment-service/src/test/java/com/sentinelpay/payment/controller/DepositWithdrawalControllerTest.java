package com.sentinelpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.dto.DepositRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.WithdrawalRequest;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.service.DepositWithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DepositWithdrawalController.class)
@Import(SecurityConfig.class)
class DepositWithdrawalControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DepositWithdrawalService depositWithdrawalService;
    @MockBean JwtTokenProvider         jwtTokenProvider;
    @MockBean UserDetailsService       userDetailsService;

    private static final String BEARER = "Bearer test-token";
    private final UUID testUserId = UUID.randomUUID();
    private final UUID walletId   = UUID.randomUUID();
    private final UUID txnId      = UUID.randomUUID();

    @BeforeEach
    void setUpJwt() {
        when(jwtTokenProvider.validate("test-token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("test-token")).thenReturn(testUserId);
        when(jwtTokenProvider.extractRole("test-token")).thenReturn("USER");
    }

    // =========================================================================
    // POST /api/v1/wallets/deposit
    // =========================================================================

    @Test
    void deposit_shouldReturn201_whenSuccessful() throws Exception {
        PaymentResponse response = buildResponse(Transaction.TransactionType.DEPOSIT, false);
        when(depositWithdrawalService.deposit(any(DepositRequest.class), eq(testUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/wallets/deposit")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildDepositRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void deposit_shouldReturn200_whenIdempotent() throws Exception {
        PaymentResponse response = buildResponse(Transaction.TransactionType.DEPOSIT, true);
        when(depositWithdrawalService.deposit(any(DepositRequest.class), eq(testUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/wallets/deposit")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildDepositRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void deposit_shouldReturn400_whenAmountMissing() throws Exception {
        DepositRequest req = new DepositRequest();
        req.setWalletId(walletId);
        req.setIdempotencyKey(UUID.randomUUID().toString());
        // amount intentionally omitted

        mockMvc.perform(post("/api/v1/wallets/deposit")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deposit_shouldReturn400_whenServiceThrows() throws Exception {
        when(depositWithdrawalService.deposit(any(), any()))
                .thenThrow(new IllegalArgumentException("Wallet not found"));

        mockMvc.perform(post("/api/v1/wallets/deposit")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildDepositRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deposit_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildDepositRequest())))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // POST /api/v1/wallets/withdraw
    // =========================================================================

    @Test
    void withdraw_shouldReturn201_whenSuccessful() throws Exception {
        PaymentResponse response = buildResponse(Transaction.TransactionType.WITHDRAWAL, false);
        when(depositWithdrawalService.withdraw(any(WithdrawalRequest.class), eq(testUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/wallets/withdraw")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildWithdrawalRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"));
    }

    @Test
    void withdraw_shouldReturn400_whenInsufficientFunds() throws Exception {
        when(depositWithdrawalService.withdraw(any(), any()))
                .thenThrow(new IllegalArgumentException("Insufficient funds"));

        mockMvc.perform(post("/api/v1/wallets/withdraw")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildWithdrawalRequest())))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DepositRequest buildDepositRequest() {
        DepositRequest req = new DepositRequest();
        req.setWalletId(walletId);
        req.setAmount(new BigDecimal("50000.00"));
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setReference("BANK-REF-001");
        return req;
    }

    private WithdrawalRequest buildWithdrawalRequest() {
        WithdrawalRequest req = new WithdrawalRequest();
        req.setWalletId(walletId);
        req.setAmount(new BigDecimal("10000.00"));
        req.setIdempotencyKey(UUID.randomUUID().toString());
        return req;
    }

    private PaymentResponse buildResponse(Transaction.TransactionType type, boolean idempotent) {
        return PaymentResponse.builder()
                .transactionId(txnId)
                .senderWalletId(walletId)
                .receiverWalletId(walletId)
                .amount(new BigDecimal("50000.00"))
                .currency("INR")
                .status(Transaction.TransactionStatus.COMPLETED)
                .type(type)
                .createdAt(Instant.now())
                .completedAt(Instant.now())
                .idempotent(idempotent)
                .build();
    }
}
