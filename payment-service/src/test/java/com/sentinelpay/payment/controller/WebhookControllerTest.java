package com.sentinelpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.WebhookRegistration;
import com.sentinelpay.payment.dto.WebhookRequest;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.data.redis.core.RedisTemplate;

@WebMvcTest(WebhookController.class)
@Import(SecurityConfig.class)
class WebhookControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean WebhookService     webhookService;
    @MockBean JwtTokenProvider   jwtTokenProvider;
    @MockBean RedisTemplate<String, String> redisTemplate;
    @MockBean UserDetailsService userDetailsService;

    private static final String BEARER = "Bearer test-token";
    private final UUID testUserId  = UUID.randomUUID();
    private final UUID webhookId   = UUID.randomUUID();

    @BeforeEach
    void setUpJwt() {
        when(jwtTokenProvider.validate("test-token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("test-token")).thenReturn(testUserId);
        when(jwtTokenProvider.extractRole("test-token")).thenReturn("USER");
    }

    // =========================================================================
    // POST /api/v1/webhooks
    // =========================================================================

    @Test
    void register_shouldReturn201_whenValid() throws Exception {
        WebhookRegistration reg = buildRegistration();
        when(webhookService.register(any(WebhookRequest.class), eq(testUserId))).thenReturn(reg);

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(webhookId.toString()))
                .andExpect(jsonPath("$.url").value("https://example.com/hook"));
    }

    @Test
    void register_shouldReturn400_whenUrlMissing() throws Exception {
        WebhookRequest req = new WebhookRequest();
        req.setEvents("TRANSACTION_COMPLETED");
        // url intentionally omitted

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest())))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET /api/v1/webhooks?userId=
    // =========================================================================

    @Test
    void list_shouldReturn200WithWebhooks() throws Exception {
        when(webhookService.listForUser(eq(testUserId), eq(testUserId)))
                .thenReturn(List.of(buildRegistration()));

        mockMvc.perform(get("/api/v1/webhooks")
                        .header("Authorization", BEARER)
                        .param("userId", testUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://example.com/hook"));
    }

    // =========================================================================
    // DELETE /api/v1/webhooks/{webhookId}
    // =========================================================================

    @Test
    void deactivate_shouldReturn204_whenSuccessful() throws Exception {
        doNothing().when(webhookService).deactivate(webhookId, testUserId);

        mockMvc.perform(delete("/api/v1/webhooks/{id}", webhookId)
                        .header("Authorization", BEARER))
                .andExpect(status().isNoContent());
    }

    @Test
    void deactivate_shouldReturn400_whenNotFound() throws Exception {
        doThrow(new IllegalArgumentException("Webhook not found"))
                .when(webhookService).deactivate(webhookId, testUserId);

        mockMvc.perform(delete("/api/v1/webhooks/{id}", webhookId)
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private WebhookRequest buildRequest() {
        WebhookRequest req = new WebhookRequest();
        req.setUrl("https://example.com/hook");
        req.setEvents("TRANSACTION_COMPLETED");
        return req;
    }

    private WebhookRegistration buildRegistration() {
        User user = User.builder().id(testUserId).email("alice@example.com").fullName("Alice").build();
        return WebhookRegistration.builder()
                .id(webhookId)
                .user(user)
                .url("https://example.com/hook")
                .events("TRANSACTION_COMPLETED")
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
