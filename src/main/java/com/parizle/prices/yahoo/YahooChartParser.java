package com.parizle.prices.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Extracts the daily bar for one trade date from a Yahoo Finance chart response.
 *
 * <p>Yahoo quotes London equities in pence ({@code GBp}/{@code GBX}) while the stored price is
 * expected in pounds, so pence values are divided by 100.
 */
@Component
public class YahooChartParser {

    private static final BigDecimal PENCE_PER_POUND = BigDecimal.valueOf(100);
    private static final int PRICE_SCALE = 8;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<DailyQuote> parse(byte[] json, String symbol, LocalDate tradeDate, ZoneId timezone) {
        JsonNode result = readResult(json, symbol);
        if (result == null) {
            return Optional.empty();
        }

        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        if (!timestamps.isArray() || timestamps.isEmpty() || quote.isMissingNode()) {
            return Optional.empty();
        }

        int index = indexOf(timestamps, tradeDate, timezone);
        if (index < 0) {
            return Optional.empty();
        }

        String currency = textOrNull(result.path("meta").path("currency"));
        boolean pence = isPence(currency);
        BigDecimal divisor = pence ? PENCE_PER_POUND : BigDecimal.ONE;

        BigDecimal close = priceAt(quote, "close", index, divisor);
        if (close == null || close.signum() <= 0) {
            return Optional.empty();
        }

        return Optional.of(new DailyQuote(
                tradeDate,
                symbol,
                pence ? "GBP" : currency,
                priceAt(quote, "open", index, divisor),
                priceAt(quote, "high", index, divisor),
                priceAt(quote, "low", index, divisor),
                close,
                volumeAt(quote, "volume", index)
        ));
    }

    private JsonNode readResult(byte[] json, String symbol) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException e) {
            throw new YahooChartException("Invalid Yahoo chart JSON for " + symbol, e);
        }

        JsonNode chart = root.path("chart");
        JsonNode error = chart.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String description = error.path("description").asText(error.toString());
            throw new YahooChartException("Yahoo chart error for " + symbol + ": " + description);
        }

        JsonNode results = chart.path("result");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    private static int indexOf(JsonNode timestamps, LocalDate tradeDate, ZoneId timezone) {
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode timestamp = timestamps.get(i);
            if (timestamp == null || !timestamp.isNumber()) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(timestamp.asLong()).atZone(timezone).toLocalDate();
            if (tradeDate.equals(date)) {
                return i;
            }
        }
        return -1;
    }

    private static BigDecimal priceAt(JsonNode quote, String field, int index, BigDecimal divisor) {
        BigDecimal value = numberAt(quote.path(field), index);
        if (value == null || divisor.compareTo(BigDecimal.ONE) == 0) {
            return value;
        }
        return value.divide(divisor, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal volumeAt(JsonNode quote, String field, int index) {
        JsonNode value = nodeAt(quote.path(field), index);
        return value != null && value.isNumber() ? BigDecimal.valueOf(value.asLong()) : null;
    }

    private static BigDecimal numberAt(JsonNode array, int index) {
        JsonNode value = nodeAt(array, index);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static JsonNode nodeAt(JsonNode array, int index) {
        if (!array.isArray() || index >= array.size()) {
            return null;
        }
        JsonNode value = array.get(index);
        return value == null || value.isNull() ? null : value;
    }

    private static boolean isPence(String currency) {
        // Yahoo writes pence as "GBp" (lower-case p); "GBP" means pounds.
        return currency != null && (currency.equals("GBp") || "GBX".equalsIgnoreCase(currency));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
