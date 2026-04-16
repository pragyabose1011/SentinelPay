package com.sentinelpay.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Extracts and validates the JWT from the {@code Authorization: Bearer <token>} header.
 *
 * <p>Validation steps:
 * <ol>
 *   <li>Signature and expiry check via {@link JwtTokenProvider#validate}</li>
 *   <li>Redis blocklist check — tokens invalidated at logout are rejected immediately</li>
 * </ol>
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider              tokenProvider;
    private final RedisTemplate<String, String> redisTemplate;
    private final String                        blocklistPrefix;

    public JwtAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            RedisTemplate<String, String> redisTemplate,
            @Value("${sentinelpay.jwt.blocklist-prefix:blocklist:}") String blocklistPrefix) {
        this.tokenProvider   = tokenProvider;
        this.redisTemplate   = redisTemplate;
        this.blocklistPrefix = blocklistPrefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token)
                && tokenProvider.validate(token)
                && !isBlocklisted(token)) {

            UUID   userId = tokenProvider.extractUserId(token);
            String role   = tokenProvider.extractRole(token);

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private boolean isBlocklisted(String token) {
        try {
            String jti    = tokenProvider.extractJti(token);
            Boolean found = redisTemplate.hasKey(blocklistPrefix + jti);
            if (Boolean.TRUE.equals(found)) {
                log.debug("Rejected blocklisted token jti={}", jti);
                return true;
            }
        } catch (Exception e) {
            // Redis failure → fail open (token is valid per signature check)
            log.warn("Blocklist check failed (Redis down?): {}", e.getMessage());
        }
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
