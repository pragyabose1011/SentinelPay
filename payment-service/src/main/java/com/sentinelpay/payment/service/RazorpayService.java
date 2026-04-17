package com.sentinelpay.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Thin client for the Razorpay REST API.
 *
 * <p>Only used when {@code sentinelpay.razorpay.enabled=true}. In dev the
 * property defaults to {@code false}, which makes the deposit flow immediately
 * credit the wallet without contacting Razorpay.
 *
 * <h3>Supported operations</h3>
 * <ul>
 *   <li>{@link #createOrder} — creates a Razorpay order for a deposit amount</li>
 *   <li>{@link #verifyWebhookSignature} — validates the {@code X-Razorpay-Signature} header</li>
 * </ul>
 */
@Service
@Slf4j
public class RazorpayService {

    private final RestClient restClient;
    private final String     webhookSecret;

    public RazorpayService(
            @Value("${sentinelpay.razorpay.key-id:rzp_test_placeholder}") String keyId,
            @Value("${sentinelpay.razorpay.key-secret:placeholder_secret}") String keySecret,
            @Value("${sentinelpay.razorpay.webhook-secret:placeholder_webhook_secret}") String webhookSecret,
            @Value("${sentinelpay.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl) {
        // requireNonNull turns missing @Value bindings into a clear startup error
        // and satisfies the IDE's strict null-safety analysis
        String resolvedBaseUrl       = Objects.requireNonNull(baseUrl,       "sentinelpay.razorpay.base-url");
        String resolvedKeyId         = Objects.requireNonNull(keyId,         "sentinelpay.razorpay.key-id");
        String resolvedKeySecret     = Objects.requireNonNull(keySecret,     "sentinelpay.razorpay.key-secret");
        this.webhookSecret           = Objects.requireNonNull(webhookSecret, "sentinelpay.razorpay.webhook-secret");
        this.restClient = RestClient.builder()
                .baseUrl(resolvedBaseUrl)
                .defaultHeaders(h -> h.setBasicAuth(resolvedKeyId, resolvedKeySecret))
                .build();
    }

    /**
     * Creates a Razorpay order and returns the Razorpay order ID (e.g. {@code order_Abc123}).
     *
     * @param amount    deposit amount in the wallet's currency
     * @param currency  ISO-4217 currency code (e.g. "INR")
     * @param receiptId idempotency key / transaction ID to use as receipt reference
     */
    public String createOrder(BigDecimal amount, String currency, String receiptId) {
        // Razorpay accepts amounts in the smallest currency unit (paise for INR, cents for USD)
        long smallestUnit = amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValue();

        Map<String, Object> body = Map.of(
                "amount",   smallestUnit,
                "currency", currency,
                "receipt",  receiptId
        );

        @SuppressWarnings({"unchecked", "null"})
        Map<String, Object> response = restClient.post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("id")) {
            throw new IllegalStateException("Razorpay order creation returned no ID");
        }
        String orderId = (String) response.get("id");
        log.info("Razorpay order created: orderId={} amount={} {}", orderId, amount, currency);
        return orderId;
    }

    /**
     * Verifies a Razorpay webhook signature.
     *
     * <p>Razorpay signs the raw request body with HMAC-SHA256 using the webhook secret
     * and sends the hex digest in the {@code X-Razorpay-Signature} header.
     *
     * @param rawPayload  the raw (unmodified) request body bytes as a string
     * @param signature   value of the {@code X-Razorpay-Signature} header
     * @return {@code true} if the signature is valid
     */
    public boolean verifyWebhookSignature(String rawPayload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);
            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Razorpay webhook signature verification failed", e);
            return false;
        }
    }
}
