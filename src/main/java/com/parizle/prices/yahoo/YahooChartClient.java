package com.parizle.prices.yahoo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class YahooChartClient {

    private static final Logger log = LoggerFactory.getLogger(YahooChartClient.class);
    private static final int MAX_JSON_BYTES = 5 * 1024 * 1024;

    private final HttpClient httpClient;
    private final YahooChartUrlBuilder urlBuilder;
    private final YahooChartParser parser;

    public YahooChartClient(HttpClient httpClient,
                            YahooChartUrlBuilder urlBuilder,
                            YahooChartParser parser) {
        this.httpClient = httpClient;
        this.urlBuilder = urlBuilder;
        this.parser = parser;
    }

    public Optional<DailyQuote> fetch(YahooFeed feed, String symbol, LocalDate tradeDate)
            throws IOException, InterruptedException {
        URI uri = urlBuilder.build(feed, symbol, tradeDate);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(feed.readTimeout())
                .header("User-Agent", feed.userAgent())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 404) {
            log.info("Yahoo has no chart for {} on {}: {}", symbol, tradeDate, uri);
            return Optional.empty();
        }
        if (response.statusCode() == 429) {
            throw new YahooChartException("Yahoo Finance rate limit (HTTP 429) for " + symbol + "; retry later");
        }
        if (response.statusCode() / 100 != 2) {
            throw new YahooChartException(
                    "Unexpected Yahoo status " + response.statusCode() + " for " + symbol);
        }

        byte[] json = response.body();
        if (json.length == 0) {
            throw new YahooChartException("Yahoo response is empty for " + symbol);
        }
        if (json.length > MAX_JSON_BYTES) {
            throw new YahooChartException("Yahoo response exceeds " + MAX_JSON_BYTES + " bytes for " + symbol);
        }

        return parser.parse(json, symbol, tradeDate, feed.timezone());
    }
}
