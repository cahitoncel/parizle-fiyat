package com.parizle.prices.frankfurter;

import com.parizle.prices.config.FrankfurterRateProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class FrankfurterRateUrlBuilder {

    private final String baseUrl;

    public FrankfurterRateUrlBuilder(FrankfurterRateProperties properties) {
        this.baseUrl = stripTrailingSlash(properties.baseUrl());
    }

    public URI build(LocalDate date, String base, Collection<String> quotes) {
        String quoteList = quotes.stream()
                .filter(quote -> quote != null && !quote.isBlank())
                .map(FrankfurterRateUrlBuilder::normalize)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .map(FrankfurterRateUrlBuilder::encode)
                .collect(Collectors.joining(","));

        String url = "%s/v2/rates?date=%s&base=%s".formatted(
                baseUrl, date, encode(normalize(base)));
        if (!quoteList.isEmpty()) {
            url += "&quotes=" + quoteList;
        }
        return URI.create(url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
