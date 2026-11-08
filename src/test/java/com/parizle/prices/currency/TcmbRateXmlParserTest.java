package com.parizle.prices.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TcmbRateXmlParserTest {

    private static final LocalDate RATE_DATE = LocalDate.of(2026, 11, 6);

    private final TcmbRateXmlParser parser = new TcmbRateXmlParser();

    @Test
    void parsesCurrenciesWithForexBuyingRates() throws Exception {
        TcmbRates rates = parser.parse(fixture(), RATE_DATE);

        assertThat(rates.rateDate()).isEqualTo(RATE_DATE);
        assertThat(rates.rates()).extracting(TcmbRate::currencyCode)
                .containsExactly("USD", "EUR", "JPY");
    }

    @Test
    void normalizesRatesThatAreQuotedForMultipleUnits() throws Exception {
        Map<String, BigDecimal> unitRates = parser.parse(fixture(), RATE_DATE).rates().stream()
                .collect(Collectors.toMap(TcmbRate::currencyCode, TcmbRate::unitRate));

        assertThat(unitRates.get("USD")).isEqualByComparingTo("49.9824");
        assertThat(unitRates.get("EUR")).isEqualByComparingTo("55.8603");
        assertThat(unitRates.get("JPY")).isEqualByComparingTo("0.31519");
    }

    @Test
    void rejectsBulletinForAnotherDate() throws Exception {
        assertThatThrownBy(() -> parser.parse(fixture(), LocalDate.of(2026, 11, 5)))
                .isInstanceOf(TcmbRateException.class)
                .hasMessageContaining("2026-11-06");
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> parser.parse("<not-xml".getBytes(), RATE_DATE))
                .isInstanceOf(TcmbRateException.class);
    }

    private static byte[] fixture() throws Exception {
        try (InputStream in = TcmbRateXmlParserTest.class.getResourceAsStream("/tcmb-rates-sample.xml")) {
            return Objects.requireNonNull(in, "Missing test fixture /tcmb-rates-sample.xml").readAllBytes();
        }
    }
}
