package com.parizle.prices.yahoo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One daily bar for a Yahoo-listed instrument. Prices are already expressed in major currency
 * units, so pence quotes are divided by 100 before this record is created.
 */
public record DailyQuote(
        LocalDate tradeDate,
        String symbol,
        String currency,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
}
