package com.parizle.prices.bulletin.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MarketPrice(
        LocalDate tradeDate,
        String instrumentCode,
        BigDecimal close,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal volume
) {
}
