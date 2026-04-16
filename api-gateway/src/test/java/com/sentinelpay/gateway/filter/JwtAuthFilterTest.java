package com.sentinelpay.gateway.filter;

import com.sentinelpay.gateway.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JwtAuthFilter}.
 *
 * <p>Uses Spring's {@link MockServerWebExchange} to build reactive server
 * exchanges without starting a full application context.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock JwtTokenProvider   jwtTokenProvider;
    @Mock GatewayFilterChain chain;

    @InjectMocks JwtAuthFilter filter;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUpChain() {
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // =========================================================================
    // Public paths — no token required
    // =========================================================================

    @Test
    void filter_shouldPassThrough_forLoginPath() {
        MockServerWebExchange exchange = exchange("/api/v1/auth/login", null);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any());
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void filter_shouldPassThrough_forRegisterPath() {
        MockServerWebExchange exchange = exchange("/api/v1/auth/register", null);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void filter_shouldPassThrough_forActuatorPath() {
        MockServerWebExchange exchange = exchange("/actuator/health", null);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any());
    }

    // =========================================================================
    // Missing token → 401
    // =========================================================================

    @Test
    void filter_shouldReturn401_whenNoAuthorizationHeader() {
        MockServerWebExchange exchange = exchange("/api/v1/payments", null);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void filter_shouldReturn401_whenBearerPrefixMissing() {
        MockServerWebExchange exchange = exchange("/api/v1/payments", "plain-token-no-bearer");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Invalid token → 401
    // =========================================================================

    @Test
    void filter_shouldReturn401_whenTokenInvalid() {
        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);
        MockServerWebExchange exchange = exchange("/api/v1/payments", "Bearer bad-token");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    // =========================================================================
    // Valid token → user context injected, request forwarded
    // =========================================================================

    @Test
    void filter_shouldForwardRequest_withUserIdAndRoleHeaders_whenTokenValid() {
        when(jwtTokenProvider.validateToken("good-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("good-token")).thenReturn(userId);
        when(jwtTokenProvider.getRole("good-token")).thenReturn("USER");

        MockServerWebExchange exchange = exchange("/api/v1/payments", "Bearer good-token");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain).filter(any());
    }

    @Test
    void filter_shouldInjectAdminRole_whenTokenHasAdminRole() {
        when(jwtTokenProvider.validateToken("admin-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("admin-token")).thenReturn(userId);
        when(jwtTokenProvider.getRole("admin-token")).thenReturn("ADMIN");

        MockServerWebExchange exchange = exchange("/api/v1/admin/users", "Bearer admin-token");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(argThat(ex ->
                "ADMIN".equals(ex.getRequest().getHeaders().getFirst("X-User-Role"))
                && userId.toString().equals(ex.getRequest().getHeaders().getFirst("X-User-Id"))));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private MockServerWebExchange exchange(String path, String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder =
                MockServerHttpRequest.get(path);
        if (authHeader != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return MockServerWebExchange.from(builder.build());
    }
}
