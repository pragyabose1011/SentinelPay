package com.sentinelpay.kyc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.kyc.config.SecurityConfig;
import com.sentinelpay.kyc.domain.KycSubmission;
import com.sentinelpay.kyc.dto.KycStatusResponse;
import com.sentinelpay.kyc.dto.KycSubmitRequest;
import com.sentinelpay.kyc.dto.ReviewRequest;
import com.sentinelpay.kyc.security.JwtTokenProvider;
import com.sentinelpay.kyc.security.UserPrincipal;
import com.sentinelpay.kyc.service.KycService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link KycController}.
 *
 * <p>SecurityConfig is imported so the real JWT filter runs. Requests carry a
 * mocked Bearer token whose user ID is injected as the authenticated principal.
 */
@WebMvcTest(KycController.class)
@Import(SecurityConfig.class)
class KycControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean KycService        kycService;
    @MockBean JwtTokenProvider  jwtTokenProvider;

    private static final String BEARER      = "Bearer test-token";
    private static final String ADMIN_TOKEN = "Bearer admin-token";

    private final UUID userId       = UUID.randomUUID();
    private final UUID adminId      = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    @BeforeEach
    void setUpJwt() {
        // Regular user token
        when(jwtTokenProvider.validateToken("test-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("test-token")).thenReturn(userId);
        when(jwtTokenProvider.getRole("test-token")).thenReturn("USER");

        // Admin token
        when(jwtTokenProvider.validateToken("admin-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("admin-token")).thenReturn(adminId);
        when(jwtTokenProvider.getRole("admin-token")).thenReturn("ADMIN");
    }

    // =========================================================================
    // POST /api/v1/kyc/submit
    // =========================================================================

    @Test
    void submit_shouldReturn201_whenValid() throws Exception {
        KycStatusResponse response = buildStatusResponse(KycSubmission.Status.PENDING);
        when(kycService.submit(any(KycSubmitRequest.class), eq(userId))).thenReturn(response);

        mockMvc.perform(post("/api/v1/kyc/submit")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildSubmitRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()));
    }

    @Test
    void submit_shouldReturn400_whenEmailMissing() throws Exception {
        KycSubmitRequest req = buildSubmitRequest();
        req.setUserEmail(null);

        mockMvc.perform(post("/api/v1/kyc/submit")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/kyc/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildSubmitRequest())))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET /api/v1/kyc/status
    // =========================================================================

    @Test
    void getOwnStatus_shouldReturn200() throws Exception {
        when(kycService.getStatus(userId))
                .thenReturn(buildStatusResponse(KycSubmission.Status.PENDING));

        mockMvc.perform(get("/api/v1/kyc/status")
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getOwnStatus_shouldReturn400_whenNotFound() throws Exception {
        when(kycService.getStatus(userId))
                .thenThrow(new IllegalArgumentException("No KYC submission found for user " + userId));

        mockMvc.perform(get("/api/v1/kyc/status")
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // GET /api/v1/kyc/status/{userId}
    // =========================================================================

    @Test
    void getStatusById_shouldReturn200_whenSameUser() throws Exception {
        when(kycService.getStatus(userId))
                .thenReturn(buildStatusResponse(KycSubmission.Status.APPROVED));

        mockMvc.perform(get("/api/v1/kyc/status/{id}", userId)
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void getStatusById_shouldReturn200_whenAdmin() throws Exception {
        UUID otherUser = UUID.randomUUID();
        when(kycService.getStatus(otherUser))
                .thenReturn(buildStatusResponse(KycSubmission.Status.PENDING));

        mockMvc.perform(get("/api/v1/kyc/status/{id}", otherUser)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void getStatusById_shouldReturn403_whenUserAccessesOthersRecord() throws Exception {
        UUID otherUser = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/kyc/status/{id}", otherUser)
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // GET /api/v1/kyc/queue (ADMIN only)
    // =========================================================================

    @Test
    void queue_shouldReturn200_whenAdmin() throws Exception {
        PageImpl<KycStatusResponse> page = new PageImpl<>(
                List.of(buildStatusResponse(KycSubmission.Status.PENDING)),
                PageRequest.of(0, 20), 1);
        when(kycService.listByStatus(eq(KycSubmission.Status.PENDING), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/kyc/queue")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void queue_shouldReturn403_whenRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/kyc/queue")
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // POST /api/v1/kyc/{submissionId}/approve
    // =========================================================================

    @Test
    void approve_shouldReturn200_whenAdmin() throws Exception {
        when(kycService.approve(eq(submissionId), eq(adminId)))
                .thenReturn(buildStatusResponse(KycSubmission.Status.APPROVED));

        mockMvc.perform(post("/api/v1/kyc/{id}/approve", submissionId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void approve_shouldReturn403_whenRegularUser() throws Exception {
        mockMvc.perform(post("/api/v1/kyc/{id}/approve", submissionId)
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // POST /api/v1/kyc/{submissionId}/reject
    // =========================================================================

    @Test
    void reject_shouldReturn200_whenAdmin() throws Exception {
        when(kycService.reject(eq(submissionId), eq(adminId), any()))
                .thenReturn(buildStatusResponse(KycSubmission.Status.REJECTED));

        ReviewRequest req = new ReviewRequest();
        req.setReason("Documents do not match");

        mockMvc.perform(post("/api/v1/kyc/{id}/reject", submissionId)
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void reject_shouldReturn409_whenAlreadyRejected() throws Exception {
        when(kycService.reject(eq(submissionId), eq(adminId), any()))
                .thenThrow(new IllegalStateException("already REJECTED"));

        ReviewRequest req = new ReviewRequest();
        req.setReason("reason");

        mockMvc.perform(post("/api/v1/kyc/{id}/reject", submissionId)
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KycSubmitRequest buildSubmitRequest() {
        KycSubmitRequest req = new KycSubmitRequest();
        req.setUserEmail("alice@example.com");
        req.setFullName("Alice");
        req.setCountryCode("IN");
        req.setDocumentType(KycSubmission.DocumentType.PASSPORT);
        req.setDocumentNumber("P1234567");
        return req;
    }

    private KycStatusResponse buildStatusResponse(KycSubmission.Status status) {
        return KycStatusResponse.builder()
                .submissionId(submissionId)
                .userId(userId)
                .userEmail("alice@example.com")
                .fullName("Alice")
                .countryCode("IN")
                .documentType(KycSubmission.DocumentType.PASSPORT)
                .documentNumber("P1234567")
                .status(status)
                .createdAt(Instant.now())
                .build();
    }
}
