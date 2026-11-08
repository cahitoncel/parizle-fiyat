package com.parizle.prices.bulletin;

import com.parizle.prices.config.BistBulletinProperties;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class BulletinUrlBuilder {

    private final String baseUrl;

    public BulletinUrlBuilder(BistBulletinProperties properties) {
        this.baseUrl = stripTrailingSlash(properties.baseUrl());
    }

    public URI build(LocalDate tradeDate, int session) {
        if (session < 1 || session > 2) {
            throw new IllegalArgumentException("Session must be 1 or 2, got " + session);
        }
        String fileName = "thb" + tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE) + session + ".zip";
        return URI.create("%s/data/thb/%d/%02d/%s".formatted(
                baseUrl, tradeDate.getYear(), tradeDate.getMonthValue(), fileName));
    }

    public URI buildViop(LocalDate tradeDate) {
        String fileName = "viop_" + tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        return URI.create("%s/data/vadeli/%s".formatted(baseUrl, fileName));
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
