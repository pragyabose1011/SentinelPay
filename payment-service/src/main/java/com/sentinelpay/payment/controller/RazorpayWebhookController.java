package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.service.DepositWithdrawalService;
import com.sentinelpay.payment.service.RazorpayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives Razorpay payment webhook events.
 *
 * <p>This endpoint is intentionally public (no JWT required) because
 * Razorpay calls it server-to-server. Request authenticity is established
 * via HMAC-SHA256 signature verification on every request.
 *
 * <pre>
 * POST /api/v1/webhooks/razorpay
 * Headers:
 *   X-Razorpay-Signature: &lt;hmac-hex&gt;
 *   Content-Type: application/json
 * </pre>
 *
 * <p>Handled events:
 * <ul>
 *   <li>{@code payment.captured} — credits the receiver wallet and marks the deposit COMPLETED</li>
 *   <li>All other events — acknowledged (200) and ignored</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
@RequiredArgsConstructor
@Slf4j
public class RazorpayWebhookController {

    private final RazorpayService          razorpayService;
    private final DepositWithdrawalService depositWithdrawalService;

    @PostMapping
    public ResponseEntity<Void> handle(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        if (signature == null || signature.isBlank()) {
            log.warn("Razorpay webhook received without signature header — rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!razorpayService.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Razorpay webhook signature mismatch — rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            depositWithdrawalService.handleRazorpayWebhook(rawBody);
        } catch (IllegalArgumentException e) {
            // Bad payload structure — tell Razorpay not to retry (400)
            log.error("Razorpay webhook processing failed (bad payload): {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            // Transient failure — let Razorpay retry (500)
            log.error("Razorpay webhook processing failed (transient): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        return ResponseEntity.ok().build();
    }
}
