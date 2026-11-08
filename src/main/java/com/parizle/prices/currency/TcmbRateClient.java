package com.parizle.prices.currency;

import com.parizle.prices.config.TcmbRateProperties;
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
public class TcmbRateClient {

    private static final Logger log = LoggerFactory.getLogger(TcmbRateClient.class);
    private static final String USER_AGENT = "parizle-prices/0.1 (+https://www.parizle.com)";
    private static final int MAX_XML_BYTES = 5 * 1024 * 1024;

    private final HttpClient httpClient;
    private final TcmbRateUrlBuilder urlBuilder;
    private final TcmbRateXmlParser parser;
    private final TcmbRateProperties properties;

    public TcmbRateClient(HttpClient httpClient,
                          TcmbRateUrlBuilder urlBuilder,
                          TcmbRateXmlParser parser,
                          TcmbRateProperties properties) {
        this.httpClient = httpClient;
        this.urlBuilder = urlBuilder;
        this.parser = parser;
        this.properties = properties;
    }

    public Optional<TcmbRates> fetch(LocalDate rateDate) throws IOException, InterruptedException {
        URI uri = urlBuilder.build(rateDate);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 404) {
            log.info("TCMB rates are not published for {}: {}", rateDate, uri);
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new TcmbRateException("Unexpected TCMB status " + response.statusCode() + " for " + uri);
        }

        byte[] xml = response.body();
        if (xml.length == 0) {
            throw new TcmbRateException("TCMB response is empty for " + uri);
        }
        if (xml.length > MAX_XML_BYTES) {
            throw new TcmbRateException("TCMB response exceeds " + MAX_XML_BYTES + " bytes for " + uri);
        }

        TcmbRates rates = parser.parse(xml, rateDate);
        log.info("Fetched TCMB rates for {}: {} currencies", rates.rateDate(), rates.rates().size());
        return Optional.of(rates);
    }
}
