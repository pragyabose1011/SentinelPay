package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.dto.AuthResponse;
import com.sentinelpay.payment.dto.LoginRequest;
import com.sentinelpay.payment.dto.RegisterRequest;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.security.JwtTokenProvider;
import com.sentinelpay.payment.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Handles user registration and login, returning signed JWTs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    @Value("${sentinelpay.jwt.expiration-ms:86400000}")
    private long expirationMs;

    /**
     * Registers a new user and returns an access token so the client can
     * start making authenticated requests immediately.
     *
     * @throws IllegalArgumentException if the email is already registered
     */
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

    /**
     * Authenticates with email + password and returns an access token.
     *
     * @throws BadCredentialsException if credentials are invalid or account not ACTIVE
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password.");
        }
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BadCredentialsException("Account is not active. Status: " + user.getStatus());
        }
        log.info("User logged in: id={}", user.getId());
        return buildAuthResponse(new UserPrincipal(user));
    }

    private AuthResponse buildAuthResponse(UserPrincipal principal) {
        String token = tokenProvider.generateToken(principal);
        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresAt(Instant.now().plusMillis(expirationMs))
                .userId(principal.getId())
                .email(principal.getEmail())
                .role(principal.getAuthorities().iterator().next()
                        .getAuthority().replace("ROLE_", ""))
                .build();
    }
}
