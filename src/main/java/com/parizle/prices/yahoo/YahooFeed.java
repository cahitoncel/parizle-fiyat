package com.parizle.prices.yahoo;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Connection settings for one Yahoo Finance feed. Each exchange job supplies its own base URL,
 * time zone and timeouts.
 */
public record YahooFeed(
        String baseUrl,
        String userAgent,
        ZoneId timezone,
        Duration readTimeout
) {
}
