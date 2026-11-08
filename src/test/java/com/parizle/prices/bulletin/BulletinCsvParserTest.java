package com.parizle.prices.bulletin;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.bulletin.domain.BulletinRow;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BulletinCsvParserTest {

    private final BulletinCsvParser parser = new BulletinCsvParser();

    @Test
    void parsesAllRowsFromSampleBulletin() throws Exception {
        List<BulletinRow> rows = parser.parse(fixture());

        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(BulletinRow::instrumentCode)
                .containsExactly("AAGYO.E", "AEFES.AOF", "AEFES.E", "THYAO.E", "TPKGY.F1", "LIDER.E");
    }

    @Test
    void parsesEquityPricesAndVolumes() throws Exception {
        BulletinRow aagyo = find("AAGYO.E");

        assertThat(aagyo.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(aagyo.instrumentName()).isEqualTo("AGAOGLU GMYO");
        assertThat(aagyo.marketSegment()).isEqualTo("Z");
        assertThat(aagyo.market()).isEqualTo("MSPOT");
        assertThat(aagyo.instrumentGroup()).isEqualTo("EQT");
        assertThat(aagyo.marketMaker()).isFalse();
        assertThat(aagyo.previousLastPrice()).isEqualByComparingTo(new BigDecimal("7.54"));
        assertThat(aagyo.openingPrice()).isEqualByComparingTo(new BigDecimal("7.52"));
        assertThat(aagyo.lowestPrice()).isEqualByComparingTo(new BigDecimal("7.4"));
        assertThat(aagyo.highestPrice()).isEqualByComparingTo(new BigDecimal("7.66"));
        assertThat(aagyo.closingPrice()).isEqualByComparingTo(new BigDecimal("7.63"));
        assertThat(aagyo.changeToPreviousClosingPct()).isEqualByComparingTo(new BigDecimal("1.194"));
        assertThat(aagyo.vwap()).isEqualByComparingTo(new BigDecimal("7.553"));
        assertThat(aagyo.totalTradedValue()).isEqualByComparingTo(new BigDecimal("33575087.15"));
        assertThat(aagyo.totalTradedVolume()).isEqualByComparingTo(new BigDecimal("4445279"));
        assertThat(aagyo.totalNumberOfContracts()).isEqualTo(3540L);
    }

    @Test
    void keepsEmptyTotalsAsNull() throws Exception {
        BulletinRow aof = find("AEFES.AOF");

        assertThat(aof.totalTradedValue()).isNull();
        assertThat(aof.totalTradedVolume()).isNull();
        assertThat(aof.totalNumberOfContracts()).isNull();
    }

    @Test
    void parsesCorporateActionAsText() throws Exception {
        assertThat(find("LIDER.E").corporateAction()).isEqualTo("06");
    }

    @Test
    void parsesShortSaleAndTradeReportFields() throws Exception {
        BulletinRow thyao = find("THYAO.E");

        assertThat(thyao.shortSell()).isTrue();
        assertThat(thyao.bist100Index()).isTrue();
        assertThat(thyao.bist30Index()).isTrue();
        assertThat(thyao.shortSaleLowestPrice()).isEqualByComparingTo(new BigDecimal("288.25"));
        assertThat(thyao.shortSaleTradedValue()).isEqualByComparingTo(new BigDecimal("811800871.25"));
        assertThat(thyao.shortSaleContracts()).isEqualTo(6212L);
        assertThat(thyao.tradeReportLowestPrice()).isEqualByComparingTo(new BigDecimal("296.5"));
        assertThat(thyao.tradeReportTradedValue()).isEqualByComparingTo(new BigDecimal("88950000"));
        assertThat(thyao.tradeReportCount()).isEqualTo(2L);
    }

    @Test
    void parsesSuspendedInstrument() throws Exception {
        assertThat(find("TPKGY.F1").suspended()).isTrue();
    }

    private BulletinRow find(String instrumentCode) throws Exception {
        return parser.parse(fixture()).stream()
                .filter(row -> row.instrumentCode().equals(instrumentCode))
                .findFirst()
                .orElseThrow();
    }

    private static Path fixture() throws Exception {
        return Path.of(Objects.requireNonNull(BulletinCsvParserTest.class.getResource("/bulletin-sample.csv")).toURI());
    }
}
