package com.parizle.prices.bulletin;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.config.BistBulletinProperties;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BulletinUrlBuilderTest {

    private final BulletinUrlBuilder builder = new BulletinUrlBuilder(properties("https://www.borsaistanbul.com"));

    @Test
    void buildsSessionOneUrl() {
        assertThat(builder.build(LocalDate.of(2026, 11, 6), 1))
                .isEqualTo(URI.create("https://www.borsaistanbul.com/data/thb/2026/11/thb202611061.zip"));
    }

    @Test
    void buildsSessionTwoUrl() {
        assertThat(builder.build(LocalDate.of(2026, 1, 9), 2))
                .isEqualTo(URI.create("https://www.borsaistanbul.com/data/thb/2026/01/thb202601092.zip"));
    }

    @Test
    void buildsViopUrl() {
        assertThat(builder.buildViop(LocalDate.of(2026, 11, 6)))
                .isEqualTo(URI.create("https://www.borsaistanbul.com/data/vadeli/viop_20261106.csv"));
    }

    @Test
    void toleratesTrailingSlashInBaseUrl() {
        BulletinUrlBuilder tolerant = new BulletinUrlBuilder(properties("https://www.borsaistanbul.com/"));
        assertThat(tolerant.build(LocalDate.of(2026, 11, 6), 1))
                .isEqualTo(URI.create("https://www.borsaistanbul.com/data/thb/2026/11/thb202611061.zip"));
    }

    private static BistBulletinProperties properties(String baseUrl) {
        return new BistBulletinProperties(
                baseUrl,
                1,
                ZoneId.of("Europe/Istanbul"),
                "0 15 19 * * MON-FRI",
                false,
                6,
                Duration.ofMinutes(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(60)
        );
    }
}
