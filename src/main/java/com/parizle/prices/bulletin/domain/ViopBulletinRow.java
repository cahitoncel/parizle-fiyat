package com.parizle.prices.bulletin.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ViopBulletinRow(
        LocalDate tradeDate,
        String instrumentSeries,
        String instrumentName,
        String market,
        String marketSegment,
        String instrumentType,
        String instrumentClass,
        String underlying,
        LocalDate expirationDate,
        BigDecimal settlementPrice,
        BigDecimal previousSettlementPrice,
        BigDecimal changeOfSettlementPricePct,
        BigDecimal openingPrice,
        BigDecimal lowestPrice,
        BigDecimal highestPrice,
        BigDecimal closingPrice,
        BigDecimal vwap,
        BigDecimal tradedValue,
        BigDecimal premiumValue,
        BigDecimal tradeVolume,
        Long tradeCount,
        Long openPosition,
        Long openPositionChange,
        BigDecimal openingSessionPrice,
        BigDecimal openingSessionTradedValue,
        BigDecimal openingSessionPremiumValue,
        BigDecimal openingSessionTradedVolume,
        Long openingSessionTradeCount
) {
}
