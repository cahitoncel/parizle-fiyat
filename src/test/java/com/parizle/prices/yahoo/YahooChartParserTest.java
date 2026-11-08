package com.parizle.prices.yahoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class YahooChartParserTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 6);

    private final YahooChartParser parser = new YahooChartParser();

    @Test
    void parsesTradeDateBarAndConvertsPenceToPounds() throws Exception {
        DailyQuote quote = parser.parse(fixture(), "VOD.L", TRADE_DATE, LONDON).orElseThrow();

        assertThat(quote.tradeDate()).isEqualTo(TRADE_DATE);
        assertThat(quote.symbol()).isEqualTo("VOD.L");
        assertThat(quote.currency()).isEqualTo("GBP");
        assertThat(quote.open()).isEqualByComparingTo(new BigDecimal("1.002"));
        assertThat(quote.high()).isEqualByComparingTo(new BigDecimal("1.034"));
        assertThat(quote.low()).isEqualByComparingTo(new BigDecimal("0.998"));
        assertThat(quote.close()).isEqualByComparingTo(new BigDecimal("1.0225"));
        assertThat(quote.volume()).isEqualByComparingTo(new BigDecimal("22222222"));
    }

    @Test
    void selectsTheBarForTheRequestedDay() throws Exception {
        DailyQuote quote = parser.parse(fixture(), "VOD.L", LocalDate.of(2026, 11, 5), LONDON)
                .orElseThrow();

        assertThat(quote.close()).isEqualByComparingTo(new BigDecimal("1.005"));
        assertThat(quote.volume()).isEqualByComparingTo(new BigDecimal("11111111"));
    }

    @Test
    void returnsEmptyWhenThereIsNoBarForTheTradeDate() throws Exception {
        assertThat(parser.parse(fixture(), "VOD.L", LocalDate.of(2026, 11, 4), LONDON)).isEmpty();
    }

    @Test
    void returnsEmptyForHolidayResponseWithoutTimestamps() {
        byte[] json = """
                {"chart":{"result":[{"meta":{"currency":"GBp","symbol":"VOD.L"},
                "timestamp":[],"indicators":{"quote":[{}]}}],"error":null}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(parser.parse(json, "VOD.L", TRADE_DATE, LONDON)).isEmpty();
    }

    @Test
    void keepsPoundQuotesUnscaled() {
        byte[] json = """
                {"chart":{"result":[{"meta":{"currency":"GBP","symbol":"BATS.L"},
                "timestamp":[1793952000],"indicators":{"quote":[{
                "open":[30.1],"high":[31.0],"low":[29.9],"close":[30.5],"volume":[5000]}]}}],"error":null}}
                """.getBytes(StandardCharsets.UTF_8);

        DailyQuote quote = parser.parse(json, "BATS.L", TRADE_DATE, LONDON).orElseThrow();

        assertThat(quote.currency()).isEqualTo("GBP");
        assertThat(quote.close()).isEqualByComparingTo(new BigDecimal("30.5"));
        assertThat(quote.high()).isEqualByComparingTo(new BigDecimal("31.0"));
    }

    @Test
    void convertsGbxTheSameWayAsPence() {
        byte[] json = """
                {"chart":{"result":[{"meta":{"currency":"GBX","symbol":"BATS.L"},
                "timestamp":[1793952000],"indicators":{"quote":[{
                "open":[null],"high":[null],"low":[null],"close":[3050],"volume":[null]}]}}],"error":null}}
                """.getBytes(StandardCharsets.UTF_8);

        DailyQuote quote = parser.parse(json, "BATS.L", TRADE_DATE, LONDON).orElseThrow();

        assertThat(quote.currency()).isEqualTo("GBP");
        assertThat(quote.close()).isEqualByComparingTo(new BigDecimal("30.5"));
        assertThat(quote.open()).isNull();
        assertThat(quote.volume()).isNull();
    }

    @Test
    void rejectsYahooErrorPayload() {
        byte[] json = """
                {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(json, "NOPE.L", TRADE_DATE, LONDON))
                .isInstanceOf(YahooChartException.class)
                .hasMessageContaining("No data found");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("not json".getBytes(StandardCharsets.UTF_8),
                "VOD.L", TRADE_DATE, LONDON))
                .isInstanceOf(YahooChartException.class);
    }

    @Test
    void returnsEmptyWhenCloseIsMissing() {
        byte[] json = """
                {"chart":{"result":[{"meta":{"currency":"GBp","symbol":"VOD.L"},
                "timestamp":[1793952000],"indicators":{"quote":[{
                "open":[100.0],"high":[101.0],"low":[99.0],"close":[null],"volume":[100]}]}}],"error":null}}
                """.getBytes(StandardCharsets.UTF_8);

        Optional<DailyQuote> quote = parser.parse(json, "VOD.L", TRADE_DATE, LONDON);

        assertThat(quote).isEmpty();
    }

    private static byte[] fixture() throws IOException {
        try (InputStream in = YahooChartParserTest.class.getResourceAsStream("/yahoo-chart-vod.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture /yahoo-chart-vod.json");
            }
            return in.readAllBytes();
        }
    }
}
