package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.dto.UserRequest;
import com.sentinelpay.payment.dto.UserResponse;
import com.sentinelpay.payment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles user registration, KYC verification, and account status management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    /**
     * Registers a new user. Email must be globally unique.
     *
     * @param request validated registration payload
     * @return the created user
     * @throws IllegalArgumentException if the email is already in use
     */
    @Transactional
    public UserResponse registerUser(UserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email is already registered: " + request.getEmail());
        }
        User user = User.builder()
                .email(request.getEmail())
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .build();
        user = userRepository.save(user);
        log.info("User registered: id={} email={}", user.getId(), user.getEmail());
        return UserResponse.from(user);
    }

    /**
     * Returns a user by ID.
     *
     * @throws IllegalArgumentException if no user exists with the given ID
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return UserResponse.from(user);
    }

    /**
     * Looks up a user by email address.
     *
     * @throws IllegalArgumentException if no user is registered with that email
     */
    @Transactional(readOnly = true)
    public UserResponse getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + email));
        return UserResponse.from(user);
    }

    /**
     * Marks a user's KYC as verified. Idempotent — calling on an already-verified user is a no-op.
     *
     * @throws IllegalArgumentException if the user does not exist or is not ACTIVE
     */
    @Transactional
    public UserResponse verifyKyc(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "KYC can only be verified for ACTIVE users. Current status: " + user.getStatus());
        }
        user.setKycVerified(true);
        user = userRepository.save(user);
        log.info("KYC verified for userId={}", userId);
        return UserResponse.from(user);
    }

    /**
     * Updates the status of a user account (SUSPENDED or CLOSED).
     *
     * <p>Rules:
     * <ul>
     *   <li>ACTIVE → SUSPENDED or CLOSED</li>
     *   <li>SUSPENDED → ACTIVE or CLOSED</li>
     *   <li>CLOSED → terminal, no transitions allowed</li>
     * </ul>
     *
     * @param userId    target user
     * @param newStatus the desired new status
     * @throws IllegalArgumentException on invalid transitions or unknown user
     */
    @Transactional
    public UserResponse updateStatus(UUID userId, User.UserStatus newStatus) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (user.getStatus() == User.UserStatus.CLOSED) {
            throw new IllegalArgumentException("Cannot change status of a CLOSED account.");
        }
        if (user.getStatus() == newStatus) {
            return UserResponse.from(user);
        }
        user.setStatus(newStatus);
        user = userRepository.save(user);
        log.info("User status updated: userId={} newStatus={}", userId, newStatus);
        return UserResponse.from(user);
    }
}
