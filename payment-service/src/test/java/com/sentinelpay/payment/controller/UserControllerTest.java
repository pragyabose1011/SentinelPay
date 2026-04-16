package com.sentinelpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.dto.UserRequest;
import com.sentinelpay.payment.dto.UserResponse;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean UserService      userService;
    @MockBean JwtTokenProvider jwtTokenProvider;

    private static final String BEARER = "Bearer test-token";
    private final UUID testUserId = UUID.randomUUID();

    @BeforeEach
    void setUpJwt() {
        when(jwtTokenProvider.validate("test-token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("test-token")).thenReturn(testUserId);
        when(jwtTokenProvider.extractRole("test-token")).thenReturn("USER");
    }

    // =========================================================================
    // POST /api/v1/users
    // =========================================================================

    @Test
    void createUser_shouldReturn201_whenValid() throws Exception {
        when(userService.createUser(any(UserRequest.class))).thenReturn(buildResponse());

        UserRequest req = new UserRequest();
        req.setEmail("new@example.com");
        req.setFullName("New User");

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(testUserId.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void createUser_shouldReturn400_whenEmailInvalid() throws Exception {
        UserRequest req = new UserRequest();
        req.setEmail("not-an-email");
        req.setFullName("Test");

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUser_shouldReturn401_whenNoToken() throws Exception {
        UserRequest req = new UserRequest();
        req.setEmail("x@example.com");
        req.setFullName("X");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET /api/v1/users/{userId}
    // =========================================================================

    @Test
    void getUser_shouldReturn200_whenFound() throws Exception {
        when(userService.getUser(testUserId)).thenReturn(buildResponse());

        mockMvc.perform(get("/api/v1/users/{id}", testUserId)
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testUserId.toString()));
    }

    @Test
    void getUser_shouldReturn400_whenNotFound() throws Exception {
        when(userService.getUser(testUserId))
                .thenThrow(new IllegalArgumentException("User not found"));

        mockMvc.perform(get("/api/v1/users/{id}", testUserId)
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // GET /api/v1/users?email=
    // =========================================================================

    @Test
    void getUserByEmail_shouldReturn200() throws Exception {
        when(userService.getUserByEmail("alice@example.com")).thenReturn(buildResponse());

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", BEARER)
                        .param("email", "alice@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    // =========================================================================
    // PATCH /api/v1/users/{userId}/kyc
    // =========================================================================

    @Test
    void verifyKyc_shouldReturn200_whenSuccessful() throws Exception {
        UserResponse verified = buildResponse();
        verified.setKycVerified(true);
        when(userService.verifyKyc(eq(testUserId), eq(testUserId))).thenReturn(verified);

        mockMvc.perform(patch("/api/v1/users/{id}/kyc", testUserId)
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycVerified").value(true));
    }

    // =========================================================================
    // PATCH /api/v1/users/{userId}/status
    // =========================================================================

    @Test
    void updateStatus_shouldReturn200_whenValid() throws Exception {
        when(userService.updateStatus(eq(testUserId), eq(User.UserStatus.SUSPENDED), eq(testUserId)))
                .thenReturn(buildResponse());

        mockMvc.perform(patch("/api/v1/users/{id}/status", testUserId)
                        .header("Authorization", BEARER)
                        .param("status", "SUSPENDED"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UserResponse buildResponse() {
        return UserResponse.builder()
                .id(testUserId)
                .email("alice@example.com")
                .fullName("Alice")
                .status(User.UserStatus.ACTIVE)
                .kycVerified(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
