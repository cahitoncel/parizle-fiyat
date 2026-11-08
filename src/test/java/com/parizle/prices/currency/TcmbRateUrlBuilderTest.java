package com.parizle.prices.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.config.TcmbRateProperties;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TcmbRateUrlBuilderTest {

    private final TcmbRateUrlBuilder builder =
            new TcmbRateUrlBuilder(properties("https://www.tcmb.gov.tr"));

    @Test
    void buildsDailyBulletinUrl() {
        assertThat(builder.build(LocalDate.of(2026, 11, 6)))
                .isEqualTo(URI.create("https://www.tcmb.gov.tr/kurlar/202611/06112026.xml"));
    }

    @Test
    void zeroPadsSingleDigitDayAndMonth() {
        assertThat(builder.build(LocalDate.of(2026, 1, 9)))
                .isEqualTo(URI.create("https://www.tcmb.gov.tr/kurlar/202601/09012026.xml"));
    }

    @Test
    void toleratesTrailingSlashInBaseUrl() {
        TcmbRateUrlBuilder tolerant =
                new TcmbRateUrlBuilder(properties("https://www.tcmb.gov.tr/"));
        assertThat(tolerant.build(LocalDate.of(2026, 11, 6)))
                .isEqualTo(URI.create("https://www.tcmb.gov.tr/kurlar/202611/06112026.xml"));
    }

    private static TcmbRateProperties properties(String baseUrl) {
        return new TcmbRateProperties(
                baseUrl,
                ZoneId.of("Europe/Istanbul"),
                "0 30 16 * * *",
                false,
                3,
                Duration.ofMinutes(10),
                Duration.ofSeconds(60)
        );
    }
}
