package com.parizle.prices.yahoo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class YahooChartUrlBuilderTest {

    private final YahooChartUrlBuilder builder = new YahooChartUrlBuilder();

    @Test
    void buildsLondonChartUrlForTradeDateWindow() {
        assertThat(builder.build(londonFeed("https://query1.finance.yahoo.com"),
                "VOD.L", LocalDate.of(2026, 11, 6)))
                .isEqualTo(URI.create("https://query1.finance.yahoo.com/v8/finance/chart/VOD.L"
                        + "?interval=1d&period1=1793318400&period2=1794009600&includePrePost=false"));
    }

    @Test
    void buildsNewYorkChartUrlForTradeDateWindow() {
        assertThat(builder.build(newYorkFeed("https://query1.finance.yahoo.com"),
                "AAPL", LocalDate.of(2026, 11, 6)))
                .isEqualTo(URI.create("https://query1.finance.yahoo.com/v8/finance/chart/AAPL"
                        + "?interval=1d&period1=1793332800&period2=1794027600&includePrePost=false"));
    }

    @Test
    void toleratesTrailingSlashInBaseUrl() {
        YahooFeed tolerant = londonFeed("https://query1.finance.yahoo.com/");

        assertThat(builder.build(tolerant, "VOD.L", LocalDate.of(2026, 11, 6)))
                .isEqualTo(builder.build(londonFeed("https://query1.finance.yahoo.com"),
                        "VOD.L", LocalDate.of(2026, 11, 6)));
    }

    @Test
    void encodesSymbolCharacters() {
        assertThat(builder.build(londonFeed("https://query1.finance.yahoo.com"),
                "^FTSE", LocalDate.of(2026, 11, 6)).getRawPath())
                .isEqualTo("/v8/finance/chart/%5EFTSE");
    }

    private static YahooFeed londonFeed(String baseUrl) {
        return new YahooFeed(baseUrl, "parizle-prices-test", ZoneId.of("Europe/London"),
                Duration.ofSeconds(30));
    }

    private static YahooFeed newYorkFeed(String baseUrl) {
        return new YahooFeed(baseUrl, "parizle-prices-test", ZoneId.of("America/New_York"),
                Duration.ofSeconds(30));
    }
}
