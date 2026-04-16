package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the rule-based fraud scoring engine.
 * TransactionRepository is mocked — no database involved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudScoringServiceTest {

    @Mock
    TransactionRepository transactionRepository;

    @InjectMocks
    FraudScoringService fraudScoringService;

    private Wallet wallet;
    private User   user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .fullName("Test User")
                .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))  // established account
                .build();

        wallet = Wallet.builder()
                .id(UUID.randomUUID())
                .user(user)
                .currency("INR")
                .balance(new BigDecimal("10000000.00"))   // ₹1 crore
                .build();

        // Default: no recent velocity
        when(transactionRepository.countBySenderWalletIdAndCreatedAtAfter(
                eq(wallet.getId()), any(Instant.class))).thenReturn(0L);
    }

    // =========================================================================
    // LOW risk
    // =========================================================================

    @Test
    void score_shouldBeLow_forSmallAmountEstablishedAccount() {
        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("50.00"));

        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo(FraudScoringService.RiskLevel.LOW);
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.detail()).isEqualTo("no_signals");
    }

    @Test
    void score_shouldBeLow_forAmountAtKycThreshold() {
        // ₹5,00,000 exactly — below the ₹10,00,000 high-value threshold, no signals should fire
        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("500000.00"));

        assertThat(result.level()).isEqualTo(FraudScoringService.RiskLevel.LOW);
    }

    // =========================================================================
    // HIGH amount rule (+40 pts)
    // =========================================================================

    @Test
    void score_shouldAddHighAmountPoints_whenAmountExceeds10000() {
        // ₹10,00,001 — just above ₹10,00,000 threshold
        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("1000001.00"));

        assertThat(result.score()).isEqualTo(40);
        assertThat(result.level()).isEqualTo(FraudScoringService.RiskLevel.MEDIUM);
        assertThat(result.detail()).contains("high_value_transfer");
    }

    @Test
    void score_shouldNotAddHighAmountPoints_whenAmountEquals10000() {
        // ₹10,00,000 exactly — boundary, no signal
        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("1000000.00"));

        assertThat(result.score()).isZero();
    }

    // =========================================================================
    // Velocity rule (+30 pts)
    // =========================================================================

    @Test
    void score_shouldAddVelocityPoints_whenMoreThan5TxInLastHour() {
        when(transactionRepository.countBySenderWalletIdAndCreatedAtAfter(
                eq(wallet.getId()), any(Instant.class))).thenReturn(6L);

        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("100.00"));

        assertThat(result.score()).isEqualTo(30);
        assertThat(result.level()).isEqualTo(FraudScoringService.RiskLevel.MEDIUM);
        assertThat(result.detail()).contains("velocity_breach");
    }

    @Test
    void score_shouldNotAddVelocityPoints_whenExactly5TxInLastHour() {
        when(transactionRepository.countBySenderWalletIdAndCreatedAtAfter(
                eq(wallet.getId()), any(Instant.class))).thenReturn(5L);

        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("100.00"));

        assertThat(result.score()).isZero();
    }

    // =========================================================================
    // New account + large transfer rule (+30 pts)
    // =========================================================================

    @Test
    void score_shouldAddNewAccountPoints_whenAccountYoungAndAmountAbove1000() {
        user.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS));   // 2-day-old account

        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("150000.00")); // ₹1,50,000

        assertThat(result.score()).isEqualTo(30);
        assertThat(result.level()).isEqualTo(FraudScoringService.RiskLevel.MEDIUM);
        assertThat(result.detail()).contains("new_account_large_transfer");
    }

    @Test
    void score_shouldNotAddNewAccountPoints_whenAmountAtOrBelow1000() {
        user.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("100000.00")); // ₹1,00,000 — at boundary

        assertThat(result.score()).isZero();
    }

    @Test
    void score_shouldNotAddNewAccountPoints_whenAccountOldEnough() {
        // account age = 30 days (from setUp) — above the 7-day threshold
        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("500000.00")); // ₹5,00,000

        assertThat(result.detail()).doesNotContain("new_account");
    }

    // =========================================================================
    // Combined rules → HIGH / blocked
    // =========================================================================

    @Test
    void score_shouldBeBlocked_whenHighAmountPlusVelocity() {
        // 40 (high amount ₹15,00,000) + 30 (velocity) = 70 → HIGH → blocked
        when(transactionRepository.countBySenderWalletIdAndCreatedAtAfter(
                eq(wallet.getId()), any(Instant.class))).thenReturn(10L);

        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("1500000.00")); // ₹15,00,000

        assertThat(result.score()).isEqualTo(70);
        assertThat(result.level()).isEqualTo(FraudScoringService.RiskLevel.HIGH);
        assertThat(result.isBlocked()).isTrue();
    }

    @Test
    void score_shouldBeBlocked_whenAllThreeRulesFire() {
        // 40 + 30 + 30 = 100: ₹15,00,000 + velocity + new account
        user.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(transactionRepository.countBySenderWalletIdAndCreatedAtAfter(
                eq(wallet.getId()), any(Instant.class))).thenReturn(10L);

        FraudScoringService.FraudResult result = fraudScoringService.score(wallet, new BigDecimal("1500000.00")); // ₹15,00,000

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.isBlocked()).isTrue();
    }

    // =========================================================================
    // toJson serialisation
    // =========================================================================

    @Test
    void fraudResult_toJson_shouldProduceValidJsonFragment() {
        FraudScoringService.FraudResult result = new FraudScoringService.FraudResult(
                30, FraudScoringService.RiskLevel.MEDIUM, "velocity_breach:count=6_in_1h");

        String json = result.toJson();

        assertThat(json).startsWith("{").endsWith("}");
        assertThat(json).contains("\"fraud_score\":30");
        assertThat(json).contains("\"risk_level\":\"MEDIUM\"");
        assertThat(json).contains("velocity_breach");
    }
}
