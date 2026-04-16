package com.sentinelpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.dto.PaymentRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.ReversalRequest;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
 * Slice test for {@link PaymentController}.
 *
 * <p>Security is active. Requests include a mocked Bearer token so the
 * {@link com.sentinelpay.payment.security.JwtAuthenticationFilter} sets a
 * {@link java.util.UUID} principal in the SecurityContext.
 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PaymentService    paymentService;
    @MockBean JwtTokenProvider  jwtTokenProvider;
    @MockBean UserDetailsService userDetailsService;

    private static final String BEARER = "Bearer test-token";
    private final UUID testUserId = UUID.randomUUID();
    private final UUID walletA    = UUID.randomUUID();
    private final UUID walletB    = UUID.randomUUID();
    private final UUID txnId      = UUID.randomUUID();

    @BeforeEach
    void setUpJwt() {
        when(jwtTokenProvider.validate("test-token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("test-token")).thenReturn(testUserId);
        when(jwtTokenProvider.extractRole("test-token")).thenReturn("USER");
    }

    // =========================================================================
    // POST /api/v1/payments
    // =========================================================================

    @Test
    void initiatePayment_shouldReturn201_whenTransferSucceeds() throws Exception {
        PaymentResponse response = buildResponse(txnId, false);
        when(paymentService.processPayment(any(PaymentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("100.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(txnId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.idempotent").value(false));
    }

    @Test
    void initiatePayment_shouldReturn200_whenRequestIsIdempotent() throws Exception {
        PaymentResponse response = buildResponse(txnId, true);
        when(paymentService.processPayment(any(PaymentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("100.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotent").value(true));
    }

    @Test
    void initiatePayment_shouldReturn400_whenServiceThrowsIllegalArgument() throws Exception {
        when(paymentService.processPayment(any(PaymentRequest.class)))
                .thenThrow(new IllegalArgumentException("Insufficient funds"));

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("99999.00"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initiatePayment_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("100.00"))))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // POST /api/v1/payments/{transactionId}/reverse
    // =========================================================================

    @Test
    void reverseTransaction_shouldReturn201_whenReversalSucceeds() throws Exception {
        PaymentResponse reversal = buildReversalResponse();
        when(paymentService.reverseTransaction(eq(txnId), any(ReversalRequest.class)))
                .thenReturn(reversal);

        ReversalRequest req = new ReversalRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setReason("customer request");

        mockMvc.perform(post("/api/v1/payments/{id}/reverse", txnId)
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.referenceTransactionId").value(txnId.toString()));
    }

    @Test
    void reverseTransaction_shouldReturn400_whenAlreadyReversed() throws Exception {
        when(paymentService.reverseTransaction(eq(txnId), any(ReversalRequest.class)))
                .thenThrow(new IllegalArgumentException("Transaction has already been reversed"));

        ReversalRequest req = new ReversalRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/payments/{id}/reverse", txnId)
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // GET /api/v1/payments/{transactionId}
    // =========================================================================

    @Test
    void getTransaction_shouldReturn200_whenFound() throws Exception {
        when(paymentService.getTransaction(txnId)).thenReturn(buildResponse(txnId, false));

        mockMvc.perform(get("/api/v1/payments/{id}", txnId)
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(txnId.toString()));
    }

    @Test
    void getTransaction_shouldReturn400_whenNotFound() throws Exception {
        when(paymentService.getTransaction(txnId))
                .thenThrow(new IllegalArgumentException("Transaction not found"));

        mockMvc.perform(get("/api/v1/payments/{id}", txnId)
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // GET /api/v1/payments?walletId=
    // =========================================================================

    @Test
    void listTransactions_shouldReturn200WithPage() throws Exception {
        PageImpl<PaymentResponse> page =
                new PageImpl<>(List.of(buildResponse(txnId, false)), PageRequest.of(0, 20), 1);
        when(paymentService.getTransactionHistory(eq(walletA), any(), any(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", BEARER)
                        .param("walletId", walletA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").value(txnId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PaymentRequest buildRequest(String amount) {
        return PaymentRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .senderWalletId(walletA)
                .receiverWalletId(walletB)
                .amount(new BigDecimal(amount))
                .currency("INR")
                .type(Transaction.TransactionType.TRANSFER)
                .build();
    }

    private PaymentResponse buildResponse(UUID id, boolean idempotent) {
        return PaymentResponse.builder()
                .transactionId(id)
                .senderWalletId(walletA)
                .receiverWalletId(walletB)
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .status(Transaction.TransactionStatus.COMPLETED)
                .type(Transaction.TransactionType.TRANSFER)
                .createdAt(Instant.now())
                .completedAt(Instant.now())
                .idempotent(idempotent)
                .build();
    }

    private PaymentResponse buildReversalResponse() {
        return PaymentResponse.builder()
                .transactionId(UUID.randomUUID())
                .senderWalletId(walletB)
                .receiverWalletId(walletA)
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .status(Transaction.TransactionStatus.COMPLETED)
                .type(Transaction.TransactionType.REVERSAL)
                .referenceTransactionId(txnId)
                .createdAt(Instant.now())
                .completedAt(Instant.now())
                .idempotent(false)
                .build();
    }
}
