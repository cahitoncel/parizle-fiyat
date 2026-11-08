package com.parizle.prices.frankfurter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FrankfurterRateParserTest {

    private final FrankfurterRateParser parser = new FrankfurterRateParser();

    @Test
    void parsesRatesForOneDate() {
        FrankfurterRates rates = parser.parse(json("""
                [{"date":"2026-11-06","base":"TRY","quote":"EUR","rate":0.017796},
                 {"date":"2026-11-06","base":"TRY","quote":"USD","rate":0.019964}]
                """), "TRY");

        assertThat(rates.rateDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(rates.rates()).containsOnlyKeys("EUR", "USD");
        assertThat(rates.rates().get("EUR")).isEqualByComparingTo("0.017796");
        assertThat(rates.rates().get("USD")).isEqualByComparingTo("0.019964");
    }

    @Test
    void normalizesQuoteCodes() {
        FrankfurterRates rates = parser.parse(json("""
                [{"date":"2026-11-06","base":"try","quote":"usd","rate":0.02}]
                """), "TRY");

        assertThat(rates.rates()).containsKey("USD");
    }

    @Test
    void rejectsMixedDates() {
        assertThatThrownBy(() -> parser.parse(json("""
                [{"date":"2026-11-06","base":"TRY","quote":"EUR","rate":0.0178},
                 {"date":"2026-11-07","base":"TRY","quote":"USD","rate":0.0199}]
                """), "TRY"))
                .isInstanceOf(FrankfurterRateException.class)
                .hasMessageContaining("mixed dates");
    }

    @Test
    void rejectsUnexpectedBase() {
        assertThatThrownBy(() -> parser.parse(json("""
                [{"date":"2026-11-06","base":"EUR","quote":"USD","rate":1.12}]
                """), "TRY"))
                .isInstanceOf(FrankfurterRateException.class)
                .hasMessageContaining("expected TRY");
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> parser.parse(json("""
                [{"date":"2026-11-06","base":"TRY","quote":"USD","rate":0}]
                """), "TRY"))
                .isInstanceOf(FrankfurterRateException.class)
                .hasMessageContaining("non-positive");
    }

    @Test
    void rejectsEmptyResponse() {
        assertThatThrownBy(() -> parser.parse(json("[]"), "TRY"))
                .isInstanceOf(FrankfurterRateException.class)
                .hasMessageContaining("no rates");
    }

    @Test
    void rejectsNonArrayResponse() {
        assertThatThrownBy(() -> parser.parse(json("{\"message\":\"not found\"}"), "TRY"))
                .isInstanceOf(FrankfurterRateException.class)
                .hasMessageContaining("no rates");
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parser.parse(json("not json"), "TRY"))
                .isInstanceOf(FrankfurterRateException.class)
                .hasMessageContaining("Invalid Frankfurter JSON");
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
