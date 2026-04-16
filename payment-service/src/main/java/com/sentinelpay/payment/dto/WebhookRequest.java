package com.sentinelpay.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for registering a new webhook endpoint.
 */
@Data
public class WebhookRequest {

    @NotBlank(message = "URL is required")
    @Pattern(regexp = "https?://.+", message = "URL must start with http:// or https://")
    @Size(max = 500)
    private String url;

    /**
     * Comma-separated list of event types to subscribe to.
     * Supported: TRANSACTION_COMPLETED, TRANSACTION_REVERSED, TRANSACTION_FAILED, FRAUD_ALERT
     */
    @NotBlank(message = "At least one event type is required")
    @Size(max = 500)
    private String events;

    /** Optional HMAC signing secret. If omitted, deliveries are unsigned. */
    @Size(max = 255)
    private String secret;
}
