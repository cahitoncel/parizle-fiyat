package com.parizle.prices.yahoo;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class YahooChartUrlBuilder {

    /** A few extra days cover weekends so the requested trade date is always inside the window. */
    private static final int LOOKBACK_DAYS = 7;

    public URI build(YahooFeed feed, String yahooSymbol, LocalDate tradeDate) {
        long period1 = tradeDate.minusDays(LOOKBACK_DAYS)
                .atStartOfDay(feed.timezone())
                .toEpochSecond();
        long period2 = tradeDate.plusDays(1)
                .atStartOfDay(feed.timezone())
                .toEpochSecond();
        String symbol = URLEncoder.encode(yahooSymbol, StandardCharsets.UTF_8).replace("+", "%20");
        String baseUrl = feed.baseUrl().endsWith("/")
                ? feed.baseUrl().substring(0, feed.baseUrl().length() - 1)
                : feed.baseUrl();

        return URI.create("%s/v8/finance/chart/%s?interval=1d&period1=%d&period2=%d&includePrePost=false"
                .formatted(baseUrl, symbol, period1, period2));
    }
}
