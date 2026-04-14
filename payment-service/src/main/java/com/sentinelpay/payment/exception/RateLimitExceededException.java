package com.sentinelpay.payment.exception;

/**
 * Thrown when a client exceeds the allowed request rate for payment operations.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
