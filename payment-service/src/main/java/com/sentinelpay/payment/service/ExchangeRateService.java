package com.sentinelpay.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Provides exchange rates for cross-currency transfers.
 *
 * <p>This implementation uses fixed rates suitable for development and testing.
 * All rates are expressed as: 1 unit of {@code from} = X units of {@code to}.
 * For production, replace with a call to an external FX provider (e.g. Open Exchange Rates).
 */
@Service
@Slf4j
public class ExchangeRateService {

    /** Rates relative to USD as the base currency. */
    private static final Map<String, BigDecimal> RATES_VS_USD = Map.of(
            "USD", BigDecimal.ONE,
            "EUR", new BigDecimal("0.9200"),
            "GBP", new BigDecimal("0.7900"),
            "INR", new BigDecimal("83.50"),
            "JPY", new BigDecimal("149.80"),
            "CAD", new BigDecimal("1.3600"),
            "AUD", new BigDecimal("1.5300"),
            "SGD", new BigDecimal("1.3400")
    );

    /**
     * Returns the exchange rate: how many units of {@code toCurrency} equal
     * one unit of {@code fromCurrency}.
     *
     * @throws IllegalArgumentException if either currency is unsupported
     */
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) return BigDecimal.ONE;

        BigDecimal fromRate = RATES_VS_USD.get(fromCurrency.toUpperCase());
        BigDecimal toRate   = RATES_VS_USD.get(toCurrency.toUpperCase());

        if (fromRate == null) throw new IllegalArgumentException("Unsupported currency: " + fromCurrency);
        if (toRate   == null) throw new IllegalArgumentException("Unsupported currency: " + toCurrency);

        // Convert: from → USD → to
        BigDecimal rate = toRate.divide(fromRate, 8, RoundingMode.HALF_UP);
        log.debug("Exchange rate {} → {}: {}", fromCurrency, toCurrency, rate);
        return rate;
    }

    /**
     * Converts an amount from one currency to another.
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        return amount.multiply(getRate(fromCurrency, toCurrency))
                     .setScale(4, RoundingMode.HALF_UP);
    }

    public boolean isSupported(String currency) {
        return RATES_VS_USD.containsKey(currency.toUpperCase());
    }
}
