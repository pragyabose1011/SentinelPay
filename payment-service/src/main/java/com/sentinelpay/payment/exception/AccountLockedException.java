package com.sentinelpay.payment.exception;

/**
 * Thrown when a user account is temporarily locked after too many failed login attempts.
 * Handled by GlobalExceptionHandler as HTTP 423 Locked.
 */
public class AccountLockedException extends RuntimeException {

    public AccountLockedException(String message) {
        super(message);
    }
}
