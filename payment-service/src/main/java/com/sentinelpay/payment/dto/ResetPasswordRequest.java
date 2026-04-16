package com.sentinelpay.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {

    @NotBlank(message = "Reset token is required")
    private String token;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String newPassword;
}
