package com.sentinelpay.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Ensures every request carries an {@code X-Correlation-Id} header.
 * If the caller already supplies one it is passed through unchanged;
 * otherwise a new UUID is generated and injected.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    public int getOrder() {
        return -200;   // runs before JwtAuthFilter
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(CORRELATION_HEADER, correlationId)
                .build();

        final String finalId = correlationId;
        return chain.filter(exchange.mutate().request(mutated).build())
                .doOnSuccess(v -> exchange.getResponse()
                        .getHeaders().add(CORRELATION_HEADER, finalId));
    }
}
