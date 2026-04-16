package com.sentinelpay.gateway.filter;

import com.sentinelpay.gateway.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global JWT authentication filter.
 *
 * <p>Runs on every request at the highest priority (order = -100).
 * Public paths (login, register, actuator) are whitelisted.
 * For authenticated paths the filter:
 * <ol>
 *   <li>Extracts the Bearer token from {@code Authorization}</li>
 *   <li>Validates the signature and expiry</li>
 *   <li>Injects {@code X-User-Id} and {@code X-User-Role} headers so downstream
 *       services know who the caller is without re-validating the JWT</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -100;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/actuator"
    );

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String token = extractToken(exchange.getRequest());
        if (token == null) {
            log.debug("No Bearer token on request to {}", path);
            return unauthorized(exchange);
        }

        if (!jwtTokenProvider.validateToken(token)) {
            log.debug("Invalid JWT on request to {}", path);
            return unauthorized(exchange);
        }

        // Inject user context headers for downstream services
        String userId = jwtTokenProvider.getUserId(token).toString();
        String role   = jwtTokenProvider.getRole(token);

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id",   userId)
                .header("X-User-Role", role != null ? role : "USER")
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private String extractToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("WWW-Authenticate", "Bearer realm=\"sentinelpay\"");
        return exchange.getResponse().setComplete();
    }
}
