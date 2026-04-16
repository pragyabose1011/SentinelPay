package com.sentinelpay.payment.exception;

/**
 * Thrown when an authenticated user attempts an action they are not authorised to perform.
 * Maps to HTTP 403 Forbidden.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
