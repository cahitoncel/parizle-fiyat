package com.parizle.prices.frankfurter;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.config.FrankfurterRateProperties;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrankfurterRateUrlBuilderTest {

    private final FrankfurterRateUrlBuilder builder =
            new FrankfurterRateUrlBuilder(properties("https://api.frankfurter.dev"));

    @Test
    void buildsDailyRatesUrlWithSortedQuotes() {
        assertThat(builder.build(LocalDate.of(2026, 11, 6), "TRY", Set.of("USD", "EUR", "GBP")))
                .isEqualTo(URI.create(
                        "https://api.frankfurter.dev/v2/rates?date=2026-11-06&base=TRY&quotes=EUR,GBP,USD"));
    }

    @Test
    void omitsQuotesWhenThereAreNone() {
        assertThat(builder.build(LocalDate.of(2026, 11, 6), "EUR", List.of()))
                .isEqualTo(URI.create("https://api.frankfurter.dev/v2/rates?date=2026-11-06&base=EUR"));
    }

    @Test
    void normalizesAndDeduplicatesQuotes() {
        assertThat(builder.build(LocalDate.of(2026, 1, 9), "usd", List.of("eur", "EUR", " eur ")))
                .isEqualTo(URI.create(
                        "https://api.frankfurter.dev/v2/rates?date=2026-01-09&base=USD&quotes=EUR"));
    }

    @Test
    void toleratesTrailingSlashInBaseUrl() {
        FrankfurterRateUrlBuilder tolerant =
                new FrankfurterRateUrlBuilder(properties("https://api.frankfurter.dev/"));
        assertThat(tolerant.build(LocalDate.of(2026, 11, 6), "TRY", List.of("USD")))
                .isEqualTo(URI.create(
                        "https://api.frankfurter.dev/v2/rates?date=2026-11-06&base=TRY&quotes=USD"));
    }

    private static FrankfurterRateProperties properties(String baseUrl) {
        return new FrankfurterRateProperties(
                baseUrl,
                ZoneId.of("Europe/Berlin"),
                "0 30 17 * * MON-FRI",
                false,
                3,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30)
        );
    }
}
