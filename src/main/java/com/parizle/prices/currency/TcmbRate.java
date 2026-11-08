package com.parizle.prices.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record TcmbRate(String currencyCode, int unit, BigDecimal forexBuying) {

    private static final int RATE_SCALE = 10;

    public BigDecimal unitRate() {
        if (unit <= 1) {
            return forexBuying.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return forexBuying.divide(BigDecimal.valueOf(unit), RATE_SCALE, RoundingMode.HALF_UP);
    }
}
