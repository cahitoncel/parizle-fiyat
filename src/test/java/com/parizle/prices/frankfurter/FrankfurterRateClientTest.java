package com.parizle.prices.frankfurter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parizle.prices.config.FrankfurterRateProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrankfurterRateClientTest {

    private static final LocalDate RATE_DATE = LocalDate.of(2026, 11, 6);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer SERVER = startServer();

    private final FrankfurterRateClient client = new FrankfurterRateClient(
            HttpClient.newHttpClient(),
            new FrankfurterRateUrlBuilder(properties()),
            new FrankfurterRateParser(),
            properties());

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void resetRequests() {
        REQUESTS.set(0);
    }

    @Test
    void fallsBackToAllRatesWhenOneQuoteIsRejected() throws Exception {
        FrankfurterRates rates = client.fetch(RATE_DATE, "TRY", List.of("GBP", "USD")).orElseThrow();

        assertThat(rates.rates()).containsKeys("EUR", "GBP", "USD");
        assertThat(rates.rates().get("USD")).isEqualByComparingTo("0.019964");
        assertThat(REQUESTS).hasValue(2);
    }

    @Test
    void returnsEmptyWhenBaseIsUnknown() throws Exception {
        assertThat(client.fetch(RATE_DATE, "ZZZ", List.of("USD"))).isEmpty();
    }

    @Test
    void failsOnUnexpectedStatus() {
        assertThatThrownBy(() -> client.fetch(RATE_DATE, "ERR", List.of("USD")))
                .isInstanceOf(FrankfurterRateException.class)
                .hasMessageContaining("500");
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/rates", FrankfurterRateClientTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        String query = exchange.getRequestURI().getQuery();
        String base = parameter(query, "base");
        String quotes = parameter(query, "quotes");

        if ("ZZZ".equals(base) || (quotes != null && quotes.contains("GBP"))) {
            send(exchange, 422, "{\"status\":422,\"message\":\"invalid currency\"}");
            return;
        }
        if ("ERR".equals(base)) {
            send(exchange, 500, "{}");
            return;
        }
        send(exchange, 200, """
                [{"date":"2026-11-06","base":"TRY","quote":"EUR","rate":0.017796},
                 {"date":"2026-11-06","base":"TRY","quote":"GBP","rate":0.014854},
                 {"date":"2026-11-06","base":"TRY","quote":"USD","rate":0.019964}]
                """);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String parameter(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static FrankfurterRateProperties properties() {
        return new FrankfurterRateProperties(
                "http://127.0.0.1:" + SERVER.getAddress().getPort(),
                ZoneId.of("Europe/Berlin"),
                "0 30 17 * * MON-FRI",
                false,
                3,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30)
        );
    }
}
