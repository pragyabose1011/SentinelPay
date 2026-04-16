package com.sentinelpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.dto.AuthResponse;
import com.sentinelpay.payment.dto.LoginRequest;
import com.sentinelpay.payment.dto.RegisterRequest;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Slice test for {@link AuthController} — no database, no Redis, no Kafka.
 * Security config is loaded but auth endpoints are permit-all.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired MockMvc         mockMvc;
    @Autowired ObjectMapper    objectMapper;

    @MockBean AuthService         authService;
    @MockBean JwtTokenProvider    jwtTokenProvider;
    @MockBean RedisTemplate<String, String> redisTemplate;
    @MockBean UserDetailsService  userDetailsService;

    // =========================================================================
    // POST /api/v1/auth/register
    // =========================================================================

    @Test
    void register_shouldReturn201WithToken_whenRequestIsValid() throws Exception {
        AuthResponse response = AuthResponse.builder()
                .accessToken("token-abc")
                .tokenType("Bearer")
                .expiresAt(Instant.now().plusSeconds(86400))
                .userId(UUID.randomUUID())
                .email("alice@example.com")
                .role("USER")
                .build();

        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        RegisterRequest request = new RegisterRequest();
        request.setEmail("alice@example.com");
        request.setFullName("Alice Smith");
        request.setPassword("password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("token-abc"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void register_shouldReturn400_whenEmailIsBlank() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("");
        request.setFullName("Alice Smith");
        request.setPassword("password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_shouldReturn400_whenPasswordTooShort() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("alice@example.com");
        request.setFullName("Alice");
        request.setPassword("short");   // < 8 chars

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // POST /api/v1/auth/login
    // =========================================================================

    @Test
    void login_shouldReturn200WithToken_whenCredentialsAreValid() throws Exception {
        AuthResponse response = AuthResponse.builder()
                .accessToken("login-token")
                .tokenType("Bearer")
                .expiresAt(Instant.now().plusSeconds(86400))
                .userId(UUID.randomUUID())
                .email("bob@example.com")
                .role("USER")
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        LoginRequest request = new LoginRequest();
        request.setEmail("bob@example.com");
        request.setPassword("password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("login-token"))
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void login_shouldReturn401_whenAuthServiceThrows() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad creds"));

        LoginRequest request = new LoginRequest();
        request.setEmail("bad@example.com");
        request.setPassword("wrongpassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_shouldReturn400_whenEmailMissing() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setPassword("password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
