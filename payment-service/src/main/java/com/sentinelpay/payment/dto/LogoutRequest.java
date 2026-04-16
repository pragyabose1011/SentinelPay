package com.sentinelpay.payment.dto;

import lombok.Data;

@Data
public class LogoutRequest {

    /** Optional — providing the refresh token allows server-side revocation. */
    private String refreshToken;
}
