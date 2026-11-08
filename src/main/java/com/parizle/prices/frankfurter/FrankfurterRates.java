package com.parizle.prices.frankfurter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * A single-day response from Frankfurter: every quote currency is priced against the requested base.
 */
public record FrankfurterRates(LocalDate rateDate, Map<String, BigDecimal> rates) {
}
