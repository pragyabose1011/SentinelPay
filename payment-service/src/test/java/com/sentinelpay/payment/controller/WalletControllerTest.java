package com.sentinelpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.WalletRequest;
import com.sentinelpay.payment.dto.WalletResponse;
import com.sentinelpay.payment.exception.ForbiddenException;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.service.WalletService;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link WalletController}.
 *
 * <p>The JWT filter is live: requests carry a mocked Bearer token that resolves
 * to {@code testUserId} so {@code @AuthenticationPrincipal UUID actorId} is populated.
 */
@WebMvcTest(WalletController.class)
@Import(SecurityConfig.class)
class WalletControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean WalletService      walletService;
    @MockBean JwtTokenProvider   jwtTokenProvider;
    @MockBean UserDetailsService userDetailsService;

    private static final String BEARER = "Bearer test-token";
    private final UUID testUserId = UUID.randomUUID();
    private final UUID walletId   = UUID.randomUUID();

    @BeforeEach
    void setUpJwt() {
        when(jwtTokenProvider.validate("test-token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("test-token")).thenReturn(testUserId);
        when(jwtTokenProvider.extractRole("test-token")).thenReturn("USER");
    }

    // =========================================================================
    // POST /api/v1/wallets
    // =========================================================================

    @Test
    void createWallet_shouldReturn201_whenRequestIsValid() throws Exception {
        WalletResponse response = buildWalletResponse(walletId, testUserId);
        when(walletService.createWallet(any(WalletRequest.class), eq(testUserId)))
                .thenReturn(response);

        WalletRequest request = new WalletRequest();
        request.setUserId(testUserId);
        request.setCurrency("INR");

        mockMvc.perform(post("/api/v1/wallets")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(walletId.toString()))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createWallet_shouldReturn400_whenCurrencyMissing() throws Exception {
        WalletRequest request = new WalletRequest();
        request.setUserId(testUserId);
        // currency intentionally omitted

        mockMvc.perform(post("/api/v1/wallets")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWallet_shouldReturn403_whenUserCreatesForSomeoneElse() throws Exception {
        when(walletService.createWallet(any(WalletRequest.class), eq(testUserId)))
                .thenThrow(new ForbiddenException("Cannot create wallet for another user"));

        WalletRequest request = new WalletRequest();
        request.setUserId(UUID.randomUUID());   // different user
        request.setCurrency("EUR");

        mockMvc.perform(post("/api/v1/wallets")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createWallet_shouldReturn401_whenNoToken() throws Exception {
        WalletRequest request = new WalletRequest();
        request.setUserId(testUserId);
        request.setCurrency("INR");

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET /api/v1/wallets/{walletId}
    // =========================================================================

    @Test
    void getWallet_shouldReturn200_whenFound() throws Exception {
        when(walletService.getWallet(walletId, testUserId))
                .thenReturn(buildWalletResponse(walletId, testUserId));

        mockMvc.perform(get("/api/v1/wallets/{id}", walletId)
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(walletId.toString()))
                .andExpect(jsonPath("$.balance").value(500.00));
    }

    @Test
    void getWallet_shouldReturn400_whenNotFound() throws Exception {
        when(walletService.getWallet(walletId, testUserId))
                .thenThrow(new IllegalArgumentException("Wallet not found"));

        mockMvc.perform(get("/api/v1/wallets/{id}", walletId)
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getWallet_shouldReturn403_whenAccessingAnotherUsersWallet() throws Exception {
        when(walletService.getWallet(walletId, testUserId))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(get("/api/v1/wallets/{id}", walletId)
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // GET /api/v1/wallets?userId=
    // =========================================================================

    @Test
    void listWallets_shouldReturn200WithList() throws Exception {
        when(walletService.getWalletsByUser(testUserId, testUserId))
                .thenReturn(List.of(
                        buildWalletResponse(walletId, testUserId),
                        buildWalletResponse(UUID.randomUUID(), testUserId)));

        mockMvc.perform(get("/api/v1/wallets")
                        .header("Authorization", BEARER)
                        .param("userId", testUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // =========================================================================
    // PATCH /api/v1/wallets/{walletId}/status
    // =========================================================================

    @Test
    void updateStatus_shouldReturn200_whenFreezing() throws Exception {
        WalletResponse frozen = buildWalletResponse(walletId, testUserId);
        frozen.setStatus(Wallet.WalletStatus.FROZEN);
        when(walletService.updateStatus(walletId, Wallet.WalletStatus.FROZEN, testUserId))
                .thenReturn(frozen);

        mockMvc.perform(patch("/api/v1/wallets/{id}/status", walletId)
                        .header("Authorization", BEARER)
                        .param("status", "FROZEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private WalletResponse buildWalletResponse(UUID id, UUID userId) {
        return WalletResponse.builder()
                .id(id)
                .userId(userId)
                .currency("INR")
                .balance(new BigDecimal("500.00"))
                .status(Wallet.WalletStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
