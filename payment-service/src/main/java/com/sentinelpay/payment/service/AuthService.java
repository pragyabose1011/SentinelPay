package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.PasswordResetToken;
import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.dto.AuthResponse;
import com.sentinelpay.payment.dto.LoginRequest;
import com.sentinelpay.payment.dto.RegisterRequest;
import com.sentinelpay.payment.exception.AccountLockedException;
import com.sentinelpay.payment.repository.PasswordResetTokenRepository;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles the full authentication lifecycle:
 * register, login, token refresh, logout, forgot-password, reset-password.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository             userRepository;
    private final PasswordEncoder            passwordEncoder;
    private final JwtTokenProvider           tokenProvider;
    private final RefreshTokenService        refreshTokenService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final EmailService               emailService;

    @Value("${sentinelpay.jwt.expiration-ms:900000}")
    private long accessExpirationMs;

    @Value("${sentinelpay.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    @Value("${sentinelpay.jwt.blocklist-prefix:blocklist:}")
    private String blocklistPrefix;

    @Value("${sentinelpay.auth.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${sentinelpay.auth.lock-duration-minutes:30}")
    private int lockDurationMinutes;

    @Value("${sentinelpay.auth.failure-window-minutes:15}")
    private int failureWindowMinutes;

    @Value("${sentinelpay.auth.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    private static final String LOGIN_LOCK_PREFIX    = "login:locked:";
    private static final String LOGIN_FAILURE_PREFIX = "login:failures:";

    // -------------------------------------------------------------------------
    // Register
    // -------------------------------------------------------------------------

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email is already registered: " + request.getEmail());
        }
        User user = User.builder()
                .email(request.getEmail())
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        user = userRepository.save(user);
        log.info("User registered: id={} email={}", user.getId(), user.getEmail());
        return buildAuthResponse(new UserPrincipal(user));
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email    = request.getEmail();
        String lockKey  = LOGIN_LOCK_PREFIX    + email;
        String failKey  = LOGIN_FAILURE_PREFIX + email;

        // Check temporary account lock set by brute-force protection
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw new AccountLockedException(
                    "Account temporarily locked due to repeated failed login attempts. Try again later.");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        boolean credentialsValid = user != null
                && passwordEncoder.matches(request.getPassword(), user.getPasswordHash())
                && user.getStatus() == User.UserStatus.ACTIVE;

        if (!credentialsValid) {
            // Increment failure counter (with sliding TTL on first increment)
            Long failures = redisTemplate.opsForValue().increment(failKey);
            if (failures != null && failures == 1) {
                redisTemplate.expire(failKey, failureWindowMinutes, TimeUnit.MINUTES);
            }
            if (failures != null && failures >= maxFailedAttempts) {
                redisTemplate.opsForValue().set(lockKey, "1", lockDurationMinutes, TimeUnit.MINUTES);
                redisTemplate.delete(failKey);
                log.warn("Account locked after {} failed attempts: email={}", failures, email);
                throw new AccountLockedException(
                        "Too many failed attempts. Account locked for " + lockDurationMinutes + " minutes.");
            }
            // Use same generic message to prevent user enumeration
            throw new BadCredentialsException("Invalid email or password.");
        }

        // Successful login — clear any accumulated failure counter
        redisTemplate.delete(failKey);
        log.info("User logged in: id={}", user.getId());
        return buildAuthResponse(new UserPrincipal(user));
    }

    // -------------------------------------------------------------------------
    // Token refresh
    // -------------------------------------------------------------------------

    /**
     * Validates the refresh token, rotates it (delete old / create new), and
     * returns a fresh access + refresh token pair.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(rawRefreshToken);
        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new IllegalStateException("User not found after token rotation"));
        UserPrincipal principal = new UserPrincipal(user);

        String newAccessToken = tokenProvider.generateToken(principal);
        Instant now = Instant.now();

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(rotation.newRawToken())
                .tokenType("Bearer")
                .expiresAt(now.plusMillis(accessExpirationMs))
                .refreshExpiresAt(now.plusMillis(refreshExpirationMs))
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    /**
     * Immediately invalidates the access token by adding its {@code jti} to the
     * Redis blocklist (TTL = remaining token validity).
     * Also revokes the provided refresh token if present.
     */
    public void logout(String rawAccessToken, String rawRefreshToken) {
        // Blocklist the access token
        try {
            String jti       = tokenProvider.extractJti(rawAccessToken);
            long   remainsMs = tokenProvider.getRemainingValidityMs(rawAccessToken);
            if (remainsMs > 0) {
                redisTemplate.opsForValue()
                        .set(blocklistPrefix + jti, "1", remainsMs, TimeUnit.MILLISECONDS);
                log.debug("Access token blocklisted: jti={} ttlMs={}", jti, remainsMs);
            }
        } catch (Exception e) {
            log.warn("Could not blocklist access token: {}", e.getMessage());
        }

        // Revoke the refresh token
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revoke(rawRefreshToken);
        }
    }

    // -------------------------------------------------------------------------
    // Forgot password
    // -------------------------------------------------------------------------

    /**
     * Generates a 1-hour password reset token and logs the reset link.
     * In Phase 4 this will send an email instead.
     *
     * <p>Always returns 200 regardless of whether the email exists, to prevent
     * user enumeration.
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = UUID.randomUUID().toString();
            PasswordResetToken entity = PasswordResetToken.builder()
                    .userId(user.getId())
                    .tokenHash(RefreshTokenService.hash(rawToken))
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
            passwordResetTokenRepository.save(entity);

            String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
            emailService.send(
                    user.getEmail(),
                    "Reset your SentinelPay password",
                    "Click the link below to reset your password (expires in 1 hour):\n\n"
                            + resetLink
                            + "\n\nIf you did not request a password reset, you can safely ignore this email.");
            log.info("Password reset email sent to userId={}", user.getId());
        });
    }

    // -------------------------------------------------------------------------
    // Reset password
    // -------------------------------------------------------------------------

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String hash = RefreshTokenService.hash(rawToken);
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHashAndUsedFalse(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset token."));

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Reset token has expired.");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        // Revoke all refresh tokens so old sessions can't be reused after a password change
        refreshTokenService.revokeAllForUser(user.getId());

        log.info("Password reset completed for userId={}", user.getId());
    }

    // -------------------------------------------------------------------------
    // Housekeeping
    // -------------------------------------------------------------------------

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredResetTokens() {
        passwordResetTokenRepository.deleteByExpiresAtBefore(Instant.now());
        log.info("Purged expired password reset tokens");
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private AuthResponse buildAuthResponse(UserPrincipal principal) {
        String accessToken  = tokenProvider.generateToken(principal);
        String refreshToken = refreshTokenService.createRefreshToken(principal.getId());
        Instant now = Instant.now();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresAt(now.plusMillis(accessExpirationMs))
                .refreshExpiresAt(now.plusMillis(refreshExpirationMs))
                .userId(principal.getId())
                .email(principal.getEmail())
                .role(principal.getAuthorities().iterator().next()
                        .getAuthority().replace("ROLE_", ""))
                .build();
    }
}
