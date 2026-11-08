package com.parizle.prices.bulletin;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.bulletin.domain.ViopBulletinRow;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ViopBulletinCsvParserTest {

    private final ViopBulletinCsvParser parser = new ViopBulletinCsvParser();

    @Test
    void parsesAllRowsFromSampleBulletin() throws Exception {
        List<ViopBulletinRow> rows = parser.parse(fixture());

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ViopBulletinRow::instrumentSeries)
                .containsExactly("F_AEFES0127", "O_USDTRYKE0127C52000", "O_TCELLE1226P94.00");
    }

    @Test
    void parsesFuturePrices() throws Exception {
        ViopBulletinRow future = find("F_AEFES0127");

        assertThat(future.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(future.instrumentName()).isEqualTo("AEFES_01/2027_VIS");
        assertThat(future.market()).isEqualTo("D_EQ");
        assertThat(future.marketSegment()).isEqualTo("SSF");
        assertThat(future.underlying()).isEqualTo("AEFES.E");
        assertThat(future.expirationDate()).isEqualTo(LocalDate.of(2027, 1, 29));
        assertThat(future.settlementPrice()).isEqualByComparingTo("18.2");
        assertThat(future.previousSettlementPrice()).isEqualByComparingTo("18.01");
        assertThat(future.changeOfSettlementPricePct()).isEqualByComparingTo("1.05");
        assertThat(future.openingPrice()).isEqualByComparingTo("17.56");
        assertThat(future.lowestPrice()).isEqualByComparingTo("17.22");
        assertThat(future.highestPrice()).isEqualByComparingTo("18.35");
        assertThat(future.closingPrice()).isEqualByComparingTo("18.35");
        assertThat(future.vwap()).isEqualByComparingTo("17.76");
        assertThat(future.tradedValue()).isEqualByComparingTo("509821");
        assertThat(future.tradeVolume()).isEqualByComparingTo("287");
        assertThat(future.tradeCount()).isEqualTo(109L);
        assertThat(future.openPosition()).isEqualTo(1961L);
        assertThat(future.openPositionChange()).isEqualTo(102L);
    }

    @Test
    void keepsMissingClosingPriceAndSessionColumnsEmpty() throws Exception {
        ViopBulletinRow option = find("O_USDTRYKE0127C52000");

        assertThat(option.closingPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(option.settlementPrice()).isEqualByComparingTo("1508.3");
        assertThat(option.openingSessionPrice()).isNull();
        assertThat(option.openingSessionTradedValue()).isNull();
        assertThat(option.openingSessionPremiumValue()).isNull();
        assertThat(option.openingSessionTradedVolume()).isNull();
        assertThat(option.openingSessionTradeCount()).isNull();
    }

    private ViopBulletinRow find(String instrumentSeries) throws Exception {
        return parser.parse(fixture()).stream()
                .filter(row -> row.instrumentSeries().equals(instrumentSeries))
                .findFirst()
                .orElseThrow();
    }

    private static Path fixture() throws Exception {
        return Path.of(Objects.requireNonNull(
                ViopBulletinCsvParserTest.class.getResource("/viop-sample.csv")).toURI());
    }
}
