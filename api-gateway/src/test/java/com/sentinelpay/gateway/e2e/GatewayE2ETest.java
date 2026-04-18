package com.sentinelpay.gateway.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * End-to-end tests for the API gateway.
 *
 * <p>Starts the real gateway on a random HTTP port. All three downstream services
 * are replaced by a single WireMock instance — the gateway cannot tell the
 * difference. Every request flows through the full filter chain:
 *
 * <ol>
 *   <li>{@code CorrelationIdFilter} — adds {@code X-Correlation-Id}
 *   <li>{@code JwtAuthFilter}       — validates token, rejects or injects user headers
 *   <li>Gateway router             — matches path, forwards to WireMock
 * </ol>
 *
 * <p>WireMock starts in a static initializer so its port is available to
 * {@link DynamicPropertySource} before the Spring context is created.
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayE2ETest {

    // -------------------------------------------------------------------------
    // Test JWT secret — must be >= 32 chars, injected into gateway via
    // @DynamicPropertySource so tokens signed here pass the gateway filter.
    // -------------------------------------------------------------------------
    private static final String TEST_SECRET =
            "e2e-test-jwt-secret-min-32-chars-sentinelpay!!";
    private static final SecretKey TEST_KEY =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    // -------------------------------------------------------------------------
    // WireMock — started statically so the port is known before the Spring
    // context boots and @DynamicPropertySource can inject it.
    // -------------------------------------------------------------------------
    static final WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + wireMock.port();
        // Disable TLS — the keystore is for prod; HTTP is fine for in-process tests
        registry.add("server.ssl.enabled", () -> "false");
        // Use the test secret for JWT validation
        registry.add("sentinelpay.jwt.secret", () -> TEST_SECRET);
        // Route all three downstream services to WireMock
        registry.add("PAYMENT_SERVICE_URL",      () -> base);
        registry.add("KYC_SERVICE_URL",          () -> base);
        registry.add("NOTIFICATION_SERVICE_URL", () -> base);
    }

    @Autowired
    WebTestClient client;

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    // =========================================================================
    // Public routes — no JWT required
    // =========================================================================

    @Test
    void login_passesThrough_withoutToken() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/auth/login"))
                .willReturn(okJson("{\"accessToken\":\"tok\",\"refreshToken\":\"ref\"}")));

        client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.com\",\"password\":\"pass\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("tok");
    }

    @Test
    void register_passesThrough_withoutToken() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/auth/register"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + UUID.randomUUID() + "\"}")));

        client.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"x@y.com\",\"password\":\"Pass1!\",\"fullName\":\"X\"}")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void actuatorHealth_isPublic() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void razorpayWebhook_isPublic() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/webhooks/razorpay"))
                .willReturn(ok()));

        client.post().uri("/api/v1/webhooks/razorpay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", "sig")
                .bodyValue("{\"event\":\"payment.captured\"}")
                .exchange()
                .expectStatus().isOk();
    }

    // =========================================================================
    // Missing / malformed token → 401
    // =========================================================================

    @Test
    void payments_returns401_whenNoToken() {
        client.get().uri("/api/v1/payments")
                .exchange()
                .expectStatus().isUnauthorized();

        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void wallets_returns401_whenNoToken() {
        client.get().uri("/api/v1/wallets")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void kyc_returns401_whenNoToken() {
        client.get().uri("/api/v1/kyc/status")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void payments_returns401_whenBearerPrefixMissing() {
        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, validToken(UUID.randomUUID(), "USER"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void payments_returns401_whenTokenSignedWithWrongSecret() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "wrong-secret-completely-different-key!!".getBytes(StandardCharsets.UTF_8));
        String badToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(wrongKey)
                .compact();

        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + badToken)
                .exchange()
                .expectStatus().isUnauthorized();

        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void payments_returns401_whenTokenExpired() {
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() - 1_000)) // already expired
                .signWith(TEST_KEY)
                .compact();

        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // =========================================================================
    // Valid USER token — gateway forwards request and injects user headers
    // =========================================================================

    @Test
    void payments_proxiedToPaymentService_withValidUserToken() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/payments"))
                .willReturn(okJson("[{\"id\":\"abc\",\"amount\":100}]")));

        UUID userId = UUID.randomUUID();
        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "USER"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("abc");
    }

    @Test
    void gateway_injectsXUserIdHeader_toDownstream() {
        UUID userId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/wallets"))
                .withHeader("X-User-Id", equalTo(userId.toString()))
                .willReturn(okJson("{\"balance\":500}")));

        client.get().uri("/api/v1/wallets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "USER"))
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/wallets"))
                .withHeader("X-User-Id", equalTo(userId.toString())));
    }

    @Test
    void gateway_injectsXUserRoleHeader_toDownstream() {
        UUID userId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/payments"))
                .withHeader("X-User-Role", equalTo("USER"))
                .willReturn(okJson("[]")));

        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "USER"))
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/payments"))
                .withHeader("X-User-Role", equalTo("USER")));
    }

    @Test
    void gateway_stripsOriginalAuthorizationHeader_fromDownstream() {
        // Downstream services should read X-User-Id, not the raw JWT
        UUID userId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/payments"))
                .willReturn(okJson("[]")));

        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "USER"))
                .exchange()
                .expectStatus().isOk();

        // Authorization header must NOT reach the downstream service
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/payments"))
                .withoutHeader(HttpHeaders.AUTHORIZATION));
    }

    // =========================================================================
    // ADMIN token — admin routes accessible
    // =========================================================================

    @Test
    void adminRoute_proxied_withAdminToken() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/admin/users"))
                .withHeader("X-User-Role", equalTo("ADMIN"))
                .willReturn(okJson("[{\"id\":\"u1\"}]")));

        UUID adminId = UUID.randomUUID();
        client.get().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(adminId, "ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("u1");
    }

    // =========================================================================
    // Route targeting — verify each path prefix reaches the right service path
    // =========================================================================

    @Test
    void kycRoute_forwardedWithOriginalPath() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/kyc/status"))
                .willReturn(okJson("{\"status\":\"PENDING\"}")));

        UUID userId = UUID.randomUUID();
        client.get().uri("/api/v1/kyc/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "USER"))
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/kyc/status")));
    }

    @Test
    void depositRoute_proxied_withValidToken() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/deposits"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"transactionId\":\"txn-1\"}")));

        UUID userId = UUID.randomUUID();
        client.post().uri("/api/v1/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":1000,\"currency\":\"INR\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.transactionId").isEqualTo("txn-1");
    }

    @Test
    void withdrawalRoute_proxied_withValidToken() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/withdrawals"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"transactionId\":\"txn-2\"}")));

        UUID userId = UUID.randomUUID();
        client.post().uri("/api/v1/withdrawals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":500,\"currency\":\"INR\"}")
                .exchange()
                .expectStatus().isCreated();
    }

    // =========================================================================
    // Correlation ID — every response must carry X-Correlation-Id
    // =========================================================================

    @Test
    void correlationId_addedToResponse_onPublicRoute() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/auth/login")).willReturn(okJson("{}")));

        client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectHeader().exists("X-Correlation-Id");
    }

    @Test
    void correlationId_addedToResponse_onProtectedRoute() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/payments")).willReturn(okJson("[]")));

        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID(), "USER"))
                .exchange()
                .expectHeader().exists("X-Correlation-Id");
    }

    @Test
    void correlationId_propagatedFromRequest_whenClientSendsIt() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/payments")).willReturn(okJson("[]")));

        String myCorrelationId = "my-trace-id-12345";
        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID(), "USER"))
                .header("X-Correlation-Id", myCorrelationId)
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", myCorrelationId);
    }

    // =========================================================================
    // Downstream error propagation
    // =========================================================================

    @Test
    void downstreamError_propagatedToClient() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Internal Server Error\"}")));

        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID(), "USER"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void downstream404_propagatedToClient() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/payments/nonexistent"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Not Found\"}")));

        client.get().uri("/api/v1/payments/nonexistent")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID(), "USER"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns a signed JWT without the "Bearer " prefix. */
    private static String token(UUID userId, String role) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /** Returns the full "Bearer <token>" header value. */
    private static String validToken(UUID userId, String role) {
        return token(userId, role); // deliberately no "Bearer " prefix for the malformed-header test
    }
}
