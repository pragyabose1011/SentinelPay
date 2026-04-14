package com.sentinelpay.payment.dto;

import com.sentinelpay.payment.domain.User;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body returned by the User API.
 */
@Data
@Builder
public class UserResponse {

    private UUID id;
    private String email;
    private String fullName;
    private String phoneNumber;
    private User.UserStatus status;
    private boolean kycVerified;
    private Instant createdAt;
    private Instant updatedAt;

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .status(user.getStatus())
                .kycVerified(user.isKycVerified())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
