package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.dto.UserRequest;
import com.sentinelpay.payment.dto.UserResponse;
import com.sentinelpay.payment.exception.ForbiddenException;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles user lifecycle management.
 * Implements {@link UserDetailsService} for Spring Security login.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;

    // -------------------------------------------------------------------------
    // Spring Security UserDetailsService
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        return new UserPrincipal(user);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        return UserResponse.from(findById(userId));
    }

    @Transactional(readOnly = true)
    public UserResponse getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + email));
        return UserResponse.from(user);
    }

    // -------------------------------------------------------------------------
    // Mutations (admin-accessible via UserController)
    // -------------------------------------------------------------------------

    @Transactional
    public UserResponse createUser(UserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + request.getEmail());
        }
        User user = User.builder()
                .email(request.getEmail())
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .build();
        user = userRepository.save(user);
        log.info("User created by admin: id={}", user.getId());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse verifyKyc(UUID targetUserId, UUID actorId) {
        assertSelfOrAdmin(targetUserId, actorId);
        User user = findById(targetUserId);
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "KYC can only be verified for ACTIVE users. Status: " + user.getStatus());
        }
        user.setKycVerified(true);
        user = userRepository.save(user);
        log.info("KYC verified for userId={} by actorId={}", targetUserId, actorId);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateStatus(UUID targetUserId, User.UserStatus newStatus, UUID actorId) {
        // Only admins can change someone else's status; users can close their own account
        if (!targetUserId.equals(actorId)) {
            assertAdmin(actorId);
        }
        User user = findById(targetUserId);
        if (user.getStatus() == User.UserStatus.CLOSED) {
            throw new IllegalArgumentException("Cannot change status of a CLOSED account.");
        }
        if (user.getStatus() == newStatus) return UserResponse.from(user);
        user.setStatus(newStatus);
        user = userRepository.save(user);
        log.info("User status updated: userId={} newStatus={} by actorId={}", targetUserId, newStatus, actorId);
        return UserResponse.from(user);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public User findById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    /** Throws {@link ForbiddenException} unless the actor IS the target or is an ADMIN. */
    public void assertSelfOrAdmin(UUID targetUserId, UUID actorId) {
        if (targetUserId.equals(actorId)) return;
        assertAdmin(actorId);
    }

    private void assertAdmin(UUID actorId) {
        User actor = findById(actorId);
        if (actor.getRole() != User.UserRole.ADMIN) {
            throw new ForbiddenException("Access denied.");
        }
    }
}
