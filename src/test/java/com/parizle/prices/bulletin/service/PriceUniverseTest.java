package com.parizle.prices.bulletin.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PriceUniverseTest {

    @Test
    void mapsBistSymbols() {
        assertThat(PriceUniverse.BIST_STOCK.toFeedSymbol("THYAO")).isEqualTo("THYAO.E");
        assertThat(PriceUniverse.BIST_STOCK.toFeedSymbol("AEFES.AOF")).isEqualTo("AEFES.AOF");
        assertThat(PriceUniverse.BIST_FUTURE.toFeedSymbol("AEFES0127")).isEqualTo("AEFES0127");
    }

    @Test
    void mapsLondonSymbols() {
        assertThat(PriceUniverse.LONDON_STOCK.toFeedSymbol("VOD")).isEqualTo("VOD.L");
        assertThat(PriceUniverse.LONDON_STOCK.toFeedSymbol("VOD.L")).isEqualTo("VOD.L");
    }

    @Test
    void leavesUsSymbolsUnchanged() {
        assertThat(PriceUniverse.US_STOCK.toFeedSymbol("AAPL")).isEqualTo("AAPL");
        assertThat(PriceUniverse.US_STOCK.toFeedSymbol("BRK-B")).isEqualTo("BRK-B");
    }

    @Test
    void normalizesHongKongSymbolsToFourDigitsAndSuffix() {
        assertThat(PriceUniverse.HONG_KONG_STOCK.toFeedSymbol("700")).isEqualTo("0700.HK");
        assertThat(PriceUniverse.HONG_KONG_STOCK.toFeedSymbol("0700")).isEqualTo("0700.HK");
        assertThat(PriceUniverse.HONG_KONG_STOCK.toFeedSymbol("5")).isEqualTo("0005.HK");
        assertThat(PriceUniverse.HONG_KONG_STOCK.toFeedSymbol("9988")).isEqualTo("9988.HK");
        assertThat(PriceUniverse.HONG_KONG_STOCK.toFeedSymbol("0700.HK")).isEqualTo("0700.HK");
        assertThat(PriceUniverse.HONG_KONG_STOCK.toFeedSymbol("700.HK")).isEqualTo("0700.HK");
    }

    @Test
    void keepsFiveDigitHongKongCodes() {
        assertThat(PriceUniverse.HONG_KONG_STOCK.toFeedSymbol("80700")).isEqualTo("80700.HK");
    }
}
