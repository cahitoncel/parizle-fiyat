package com.parizle.prices.frankfurter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Parses the Frankfurter v2 {@code /rates} response:
 * {@code [{"date":"2026-11-06","base":"TRY","quote":"USD","rate":0.019964}, ...]}.
 */
@Component
public class FrankfurterRateParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public FrankfurterRates parse(byte[] json, String expectedBase) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException e) {
            throw new FrankfurterRateException("Invalid Frankfurter JSON for base " + expectedBase, e);
        }
        if (!root.isArray() || root.isEmpty()) {
            throw new FrankfurterRateException("Frankfurter returned no rates for base " + expectedBase);
        }

        LocalDate rateDate = null;
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (JsonNode node : root) {
            LocalDate date = date(node, expectedBase);
            if (rateDate == null) {
                rateDate = date;
            } else if (!rateDate.equals(date)) {
                throw new FrankfurterRateException(
                        "Frankfurter returned mixed dates for base " + expectedBase + ": "
                                + rateDate + " and " + date);
            }

            String base = text(node, "base", expectedBase);
            if (!expectedBase.equalsIgnoreCase(base)) {
                throw new FrankfurterRateException(
                        "Frankfurter returned base " + base + " but expected " + expectedBase);
            }

            String quote = text(node, "quote", expectedBase).toUpperCase(Locale.ROOT);
            JsonNode rateNode = node.path("rate");
            if (!rateNode.isNumber()) {
                throw new FrankfurterRateException(
                        "Frankfurter response has no numeric rate for " + base + "/" + quote);
            }
            BigDecimal rate = rateNode.decimalValue();
            if (rate.signum() <= 0) {
                throw new FrankfurterRateException(
                        "Frankfurter response has a non-positive rate for " + base + "/" + quote);
            }
            rates.put(quote, rate);
        }
        return new FrankfurterRates(rateDate, Map.copyOf(rates));
    }

    private static LocalDate date(JsonNode node, String base) {
        String value = text(node, "date", base);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new FrankfurterRateException(
                    "Invalid Frankfurter date '" + value + "' for base " + base, e);
        }
    }

    private static String text(JsonNode node, String field, String base) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new FrankfurterRateException(
                    "Frankfurter response is missing '" + field + "' for base " + base);
        }
        return value.asText().trim();
    }
}
