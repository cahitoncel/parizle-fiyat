package com.parizle.prices.frankfurter;

import com.parizle.prices.config.FrankfurterRateProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FrankfurterRateClient {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterRateClient.class);
    private static final String USER_AGENT = "parizle-prices/0.1 (+https://www.parizle.com)";
    private static final int MAX_JSON_BYTES = 5 * 1024 * 1024;

    private final HttpClient httpClient;
    private final FrankfurterRateUrlBuilder urlBuilder;
    private final FrankfurterRateParser parser;
    private final FrankfurterRateProperties properties;

    public FrankfurterRateClient(HttpClient httpClient,
                                 FrankfurterRateUrlBuilder urlBuilder,
                                 FrankfurterRateParser parser,
                                 FrankfurterRateProperties properties) {
        this.httpClient = httpClient;
        this.urlBuilder = urlBuilder;
        this.parser = parser;
        this.properties = properties;
    }

    public Optional<FrankfurterRates> fetch(LocalDate date, String base, Collection<String> quotes)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send(urlBuilder.build(date, base, quotes));

        // A single unsupported quote currency fails the whole request with 422, so fall back to
        // asking for every currency and let the caller keep the ones it needs.
        if (response.statusCode() == 422 && !quotes.isEmpty()) {
            log.warn("Frankfurter rejected the quote list {} for base {}; retrying without quotes",
                    quotes, base);
            response = send(urlBuilder.build(date, base, List.of()));
        }

        if (response.statusCode() == 404 || response.statusCode() == 422) {
            log.warn("Frankfurter has no rates for base {} on {} (HTTP {})",
                    base, date, response.statusCode());
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new FrankfurterRateException(
                    "Unexpected Frankfurter status " + response.statusCode() + " for base " + base);
        }

        byte[] json = response.body();
        if (json.length == 0) {
            throw new FrankfurterRateException("Frankfurter response is empty for base " + base);
        }
        if (json.length > MAX_JSON_BYTES) {
            throw new FrankfurterRateException(
                    "Frankfurter response exceeds " + MAX_JSON_BYTES + " bytes for base " + base);
        }

        FrankfurterRates rates = parser.parse(json, base);
        log.info("Fetched Frankfurter rates for {}: {} quotes", rates.rateDate(), rates.rates().size());
        return Optional.of(rates);
    }

    private HttpResponse<byte[]> send(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
