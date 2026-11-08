package com.parizle.prices.frankfurter;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.bulletin.domain.ImportStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "bist.bulletin.scheduling-enabled=false",
        "bist.viop.scheduling-enabled=false",
        "tcmb.rates.scheduling-enabled=false",
        "london.prices.scheduling-enabled=false",
        "us.prices.scheduling-enabled=false",
        "hongkong.prices.scheduling-enabled=false",
        "frankfurter.rates.scheduling-enabled=false",
        "frankfurter.rates.max-attempts=1",
        "frankfurter.rates.retry-delay=1s",
        "spring.datasource.password=test"
})
@Testcontainers
class FrankfurterRateImportIntegrationTest {

    private static final LocalDate RATE_DATE = LocalDate.of(2026, 11, 6);
    private static final Map<String, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, String> QUOTES = new ConcurrentHashMap<>();
    private static final HttpServer SERVER = startServer();
    private static final String BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();

    private static final UUID PORTFOLIO_USD = UUID.fromString("019b76da-a800-7001-8000-000000000042");
    private static final UUID PORTFOLIO_EUR = UUID.fromString("019b76da-a800-7001-8000-000000000045");
    private static final UUID PORTFOLIO_TRY = UUID.fromString("019b76da-a800-7001-8000-000000000041");
    private static final UUID PORTFOLIO_EMPTY = UUID.fromString("019b76da-a800-7001-8000-000000000043");
    private static final UUID PORTFOLIO_DELETED = UUID.fromString("019b76da-a800-7001-8000-000000000044");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void frankfurterProperties(DynamicPropertyRegistry registry) {
        registry.add("frankfurter.rates.base-url", () -> BASE_URL);
    }

    @Autowired
    private FrankfurterRateImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void setUpDatabase() {
        REQUESTS.clear();
        QUOTES.clear();

        jdbcTemplate.execute("DROP TABLE IF EXISTS currency_rate");
        jdbcTemplate.execute("DROP TABLE IF EXISTS bank_account");
        jdbcTemplate.execute("DROP TABLE IF EXISTS bank");
        jdbcTemplate.execute("DROP TABLE IF EXISTS portfolio");
        jdbcTemplate.execute("DROP TABLE IF EXISTS currency");

        jdbcTemplate.execute("CREATE TABLE currency (code char(3) PRIMARY KEY)");
        jdbcTemplate.execute("INSERT INTO currency (code) VALUES ('TRY'), ('USD'), ('EUR'), ('GBP'), ('CHF')");
        jdbcTemplate.execute("""
                CREATE TABLE portfolio (
                    id uuid PRIMARY KEY,
                    portfolio_name text NOT NULL,
                    currency char(3) NOT NULL REFERENCES currency(code),
                    is_currency_base boolean NOT NULL DEFAULT true,
                    deleted_at timestamptz
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE bank (
                    id uuid PRIMARY KEY,
                    portfolio_id uuid NOT NULL REFERENCES portfolio(id),
                    bank_name text NOT NULL,
                    deleted_at timestamptz
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE bank_account (
                    id uuid PRIMARY KEY,
                    bank_id uuid NOT NULL REFERENCES bank(id),
                    account_name text NOT NULL,
                    currency_code char(3) NOT NULL REFERENCES currency(code),
                    is_active boolean NOT NULL DEFAULT true,
                    deleted_at timestamptz
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE currency_rate (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    portfolio_id uuid NOT NULL REFERENCES portfolio(id) ON DELETE CASCADE,
                    currency char(3) NOT NULL REFERENCES currency(code),
                    rate_date date NOT NULL,
                    rate numeric NOT NULL CHECK (rate > 0),
                    created_at timestamptz NOT NULL DEFAULT now(),
                    deleted_at timestamptz
                )
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX currency_rate_uk
                    ON currency_rate (portfolio_id, currency, rate_date) WHERE deleted_at IS NULL
                """);

        insertPortfolio(PORTFOLIO_USD, "USD portfolio", "USD", true, null);
        insertPortfolio(PORTFOLIO_EUR, "EUR portfolio", "EUR", false, null);
        insertPortfolio(PORTFOLIO_TRY, "TRY portfolio", "TRY", false, null);
        insertPortfolio(PORTFOLIO_EMPTY, "Empty portfolio", "CHF", true, null);
        insertPortfolio(PORTFOLIO_DELETED, "Deleted portfolio", "USD", true, OffsetDateTime.now());

        UUID usdBank = UUID.randomUUID();
        UUID eurBank = UUID.randomUUID();
        UUID tryBank = UUID.randomUUID();
        UUID deletedBank = UUID.randomUUID();
        UUID deletedPortfolioBank = UUID.randomUUID();
        insertBank(usdBank, PORTFOLIO_USD, "USD bank", null);
        insertBank(eurBank, PORTFOLIO_EUR, "EUR bank", null);
        insertBank(tryBank, PORTFOLIO_TRY, "TRY bank", null);
        insertBank(deletedBank, PORTFOLIO_EUR, "Deleted bank", OffsetDateTime.now());
        insertBank(deletedPortfolioBank, PORTFOLIO_DELETED, "Deleted portfolio bank", null);

        insertAccount(usdBank, "EUR account", "EUR", null);
        insertAccount(usdBank, "GBP account", "GBP", null);
        insertAccount(eurBank, "USD account", "USD", null);
        insertAccount(tryBank, "EUR account", "EUR", null);
        insertAccount(tryBank, "USD account", "USD", null);
        insertAccount(deletedBank, "CHF account", "CHF", null);
        insertAccount(deletedPortfolioBank, "EUR account", "EUR", null);
    }

    @Test
    void writesRatesForNonTryPortfoliosWithTheirCurrencyAsBase() {
        FrankfurterRateImportOutcome outcome = importService.importRates(RATE_DATE);

        assertThat(outcome.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(outcome.rateDate()).isEqualTo(RATE_DATE);
        assertThat(outcome.baseCount()).isEqualTo(2);
        assertThat(outcome.portfolioCount()).isEqualTo(2);
        assertThat(outcome.requiredCurrencyCount()).isEqualTo(3);
        assertThat(outcome.writtenRows()).isEqualTo(2);
        assertThat(outcome.existingRows()).isZero();
        assertThat(outcome.missingCurrencies()).containsExactly("GBP");

        Map<String, BigDecimal> usdRates = rates(PORTFOLIO_USD);
        assertThat(usdRates).containsOnlyKeys("EUR");
        assertThat(usdRates.get("EUR")).isEqualByComparingTo("0.9");

        Map<String, BigDecimal> eurRates = rates(PORTFOLIO_EUR);
        assertThat(eurRates).containsOnlyKeys("USD");
        assertThat(eurRates.get("USD")).isEqualByComparingTo("0.8620689655");

        assertThat(rates(PORTFOLIO_TRY)).isEmpty();
        assertThat(rates(PORTFOLIO_EMPTY)).isEmpty();
        assertThat(rates(PORTFOLIO_DELETED)).isEmpty();
        assertThat(count("SELECT count(*) FROM currency_rate")).isEqualTo(2);

        assertThat(requests("USD")).isEqualTo(1);
        assertThat(requests("EUR")).isEqualTo(1);
        assertThat(requests("TRY")).isZero();
        assertThat(QUOTES.get("USD")).isEqualTo("EUR,GBP");
        assertThat(QUOTES.get("EUR")).isEqualTo("USD");
    }

    @Test
    void rerunLeavesExistingRatesUntouched() {
        importService.importRates(RATE_DATE);
        UUID firstId = jdbcTemplate.queryForObject(
                "SELECT id FROM currency_rate WHERE portfolio_id = ? AND currency = 'EUR'",
                UUID.class, PORTFOLIO_USD);

        FrankfurterRateImportOutcome second = importService.importRates(RATE_DATE);
        UUID secondId = jdbcTemplate.queryForObject(
                "SELECT id FROM currency_rate WHERE portfolio_id = ? AND currency = 'EUR'",
                UUID.class, PORTFOLIO_USD);

        assertThat(second.writtenRows()).isZero();
        assertThat(second.existingRows()).isEqualTo(2);
        assertThat(secondId).isEqualTo(firstId);
        assertThat(count("SELECT count(*) FROM currency_rate")).isEqualTo(2);
    }

    @Test
    void recordsUnavailableRatesWithoutWriting() {
        FrankfurterRateImportOutcome outcome = importService.importRates(LocalDate.of(2026, 11, 7));

        assertThat(outcome.status()).isEqualTo(ImportStatus.NOT_PUBLISHED);
        assertThat(outcome.writtenRows()).isZero();
        assertThat(count("SELECT count(*) FROM currency_rate")).isZero();
    }

    private Map<String, BigDecimal> rates(UUID portfolioId) {
        return jdbcTemplate.query("""
                        SELECT currency, rate FROM currency_rate
                        WHERE portfolio_id = ? AND deleted_at IS NULL
                        """,
                rs -> {
                    Map<String, BigDecimal> rates = new LinkedHashMap<>();
                    while (rs.next()) {
                        rates.put(rs.getString("currency").trim(), rs.getBigDecimal("rate"));
                    }
                    return rates;
                },
                portfolioId);
    }

    private void insertPortfolio(UUID id, String name, String currency, boolean isCurrencyBase,
                                 OffsetDateTime deletedAt) {
        jdbcTemplate.update("INSERT INTO portfolio VALUES (?, ?, ?, ?, ?)",
                id, name, currency, isCurrencyBase, deletedAt);
    }

    private void insertBank(UUID id, UUID portfolioId, String name, OffsetDateTime deletedAt) {
        jdbcTemplate.update("INSERT INTO bank VALUES (?, ?, ?, ?)", id, portfolioId, name, deletedAt);
    }

    private void insertAccount(UUID bankId, String name, String currency, OffsetDateTime deletedAt) {
        jdbcTemplate.update("INSERT INTO bank_account VALUES (?, ?, ?, ?, true, ?)",
                UUID.randomUUID(), bankId, name, currency, deletedAt);
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static int requests(String base) {
        AtomicInteger count = REQUESTS.get(base);
        return count == null ? 0 : count.get();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/rates", FrankfurterRateImportIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        Map<String, String> parameters = parameters(exchange.getRequestURI().getQuery());
        String base = parameters.getOrDefault("base", "");
        REQUESTS.computeIfAbsent(base, ignored -> new AtomicInteger()).incrementAndGet();
        if (parameters.containsKey("quotes")) {
            QUOTES.put(base, parameters.get("quotes"));
        }

        byte[] body = RATE_DATE.toString().equals(parameters.get("date"))
                ? response(base)
                : null;
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] response(String base) {
        String json = switch (base) {
            case "USD" -> """
                    [{"date":"2026-11-06","base":"USD","quote":"EUR","rate":0.9}]
                    """;
            case "EUR" -> """
                    [{"date":"2026-11-06","base":"EUR","quote":"USD","rate":1.16}]
                    """;
            default -> null;
        };
        return json == null ? null : json.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                parameters.put(
                        URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return parameters;
    }
}
