package com.parizle.prices.currency;

import com.parizle.prices.config.TcmbRateProperties;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class TcmbRateUrlBuilder {

    private final String baseUrl;

    public TcmbRateUrlBuilder(TcmbRateProperties properties) {
        this.baseUrl = stripTrailingSlash(properties.baseUrl());
    }

    public URI build(LocalDate date) {
        String fileName = "%02d%02d%d.xml".formatted(
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
        return URI.create("%s/kurlar/%d%02d/%s".formatted(
                baseUrl, date.getYear(), date.getMonthValue(), fileName));
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
