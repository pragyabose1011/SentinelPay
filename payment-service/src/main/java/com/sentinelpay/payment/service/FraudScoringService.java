package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based fraud scoring engine.
 *
 * <p>Evaluates each payment against a set of risk rules and returns a {@link FraudResult}
 * containing a 0–100 score, a risk level, and a human-readable explanation.
 *
 * <table>
 *   <caption>Scoring rules</caption>
 *   <tr><th>Rule</th><th>Points</th></tr>
 *   <tr><td>Amount &gt; $10,000</td><td>+40</td></tr>
 *   <tr><td>Velocity: &gt;5 transactions from sender wallet in last hour</td><td>+30</td></tr>
 *   <tr><td>New account (&lt;7 days) + amount &gt; $1,000</td><td>+30</td></tr>
 * </table>
 *
 * <p>Risk levels:
 * <ul>
 *   <li>LOW  (0–29):  allow normally</li>
 *   <li>MEDIUM (30–59): allow but flag in transaction metadata</li>
 *   <li>HIGH (60+):   block the transaction</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudScoringService {

    private static final BigDecimal HIGH_AMOUNT_THRESHOLD = new BigDecimal("10000");
    private static final BigDecimal NEW_ACCOUNT_AMOUNT_THRESHOLD = new BigDecimal("1000");
    private static final int HIGH_AMOUNT_SCORE = 40;
    private static final int VELOCITY_SCORE = 30;
    private static final int NEW_ACCOUNT_SCORE = 30;
    private static final int VELOCITY_LIMIT = 5;
    private static final int NEW_ACCOUNT_DAYS = 7;

    private final TransactionRepository transactionRepository;

    /**
     * Scores a payment attempt before it is committed.
     *
     * @param senderWallet the wallet being debited
     * @param amount       transfer amount
     * @return a {@link FraudResult} describing the risk assessment
     */
    public FraudResult score(Wallet senderWallet, BigDecimal amount) {
        int totalScore = 0;
        List<String> reasons = new ArrayList<>();

        // Rule 1: High-value transaction
        if (amount.compareTo(HIGH_AMOUNT_THRESHOLD) > 0) {
            totalScore += HIGH_AMOUNT_SCORE;
            reasons.add("high_value_transfer:" + amount.toPlainString());
        }

        // Rule 2: Velocity — too many recent transactions from this wallet
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        long recentCount = transactionRepository.countBySenderWalletIdAndCreatedAtAfter(
                senderWallet.getId(), oneHourAgo);
        if (recentCount > VELOCITY_LIMIT) {
            totalScore += VELOCITY_SCORE;
            reasons.add("velocity_breach:count=" + recentCount + "_in_1h");
        }

        // Rule 3: New account making a substantial transfer
        User user = senderWallet.getUser();
        Instant accountAgeCutoff = Instant.now().minus(NEW_ACCOUNT_DAYS, ChronoUnit.DAYS);
        if (user.getCreatedAt().isAfter(accountAgeCutoff)
                && amount.compareTo(NEW_ACCOUNT_AMOUNT_THRESHOLD) > 0) {
            totalScore += NEW_ACCOUNT_SCORE;
            reasons.add("new_account_large_transfer:account_age_days<" + NEW_ACCOUNT_DAYS);
        }

        RiskLevel level = resolveLevel(totalScore);
        String detail = reasons.isEmpty() ? "no_signals" : String.join(",", reasons);
        log.debug("Fraud score: walletId={} score={} level={} detail={}",
                senderWallet.getId(), totalScore, level, detail);

        return new FraudResult(totalScore, level, detail);
    }

    private RiskLevel resolveLevel(int score) {
        if (score >= 60) return RiskLevel.HIGH;
        if (score >= 30) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    public record FraudResult(int score, RiskLevel level, String detail) {
        public boolean isBlocked() {
            return level == RiskLevel.HIGH;
        }

        /** Serialises the result to a JSON fragment for storage in transaction metadata. */
        public String toJson() {
            return String.format(
                    "{\"fraud_score\":%d,\"risk_level\":\"%s\",\"signals\":\"%s\"}",
                    score, level.name(), detail.replace("\"", "'")
            );
        }
    }
}
