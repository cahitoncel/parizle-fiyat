package com.parizle.prices.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.bulletin.domain.ImportStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
        "tcmb.rates.max-attempts=1",
        "tcmb.rates.retry-delay=1s",
        "spring.datasource.password=test"
})
@Testcontainers
class CurrencyRateImportIntegrationTest {

    private static final LocalDate RATE_DATE = LocalDate.of(2026, 11, 6);
    private static final byte[] XML = readFixture();
    private static final HttpServer SERVER = startServer();
    private static final String BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();

    private static final UUID PORTFOLIO_TRY = UUID.fromString("019b76da-a800-7001-8000-000000000021");
    private static final UUID PORTFOLIO_USD = UUID.fromString("019b76da-a800-7001-8000-000000000022");
    private static final UUID PORTFOLIO_DELETED = UUID.fromString("019b76da-a800-7001-8000-000000000023");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void tcmbProperties(DynamicPropertyRegistry registry) {
        registry.add("tcmb.rates.base-url", () -> BASE_URL);
    }

    @Autowired
    private CurrencyRateImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void setUpDatabase() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS currency_rate");
        jdbcTemplate.execute("DROP TABLE IF EXISTS bank_account");
        jdbcTemplate.execute("DROP TABLE IF EXISTS bank");
        jdbcTemplate.execute("DROP TABLE IF EXISTS portfolio");
        jdbcTemplate.execute("DROP TABLE IF EXISTS currency");

        jdbcTemplate.execute("CREATE TABLE currency (code char(3) PRIMARY KEY)");
        jdbcTemplate.execute("INSERT INTO currency (code) VALUES ('TRY'), ('USD'), ('EUR'), ('JPY'), ('GBP'), ('CHF')");
        jdbcTemplate.execute("""
                CREATE TABLE portfolio (
                    id uuid PRIMARY KEY,
                    portfolio_name text NOT NULL,
                    currency char(3) NOT NULL REFERENCES currency(code),
                    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
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

        jdbcTemplate.update("INSERT INTO portfolio VALUES (?, 'TRY portfolio', 'TRY', 'ACTIVE', NULL)",
                PORTFOLIO_TRY);
        jdbcTemplate.update("INSERT INTO portfolio VALUES (?, 'USD portfolio', 'USD', 'ACTIVE', NULL)",
                PORTFOLIO_USD);
        jdbcTemplate.update("INSERT INTO portfolio VALUES (?, 'Deleted portfolio', 'TRY', 'ACTIVE', now())",
                PORTFOLIO_DELETED);

        UUID tryBank = UUID.randomUUID();
        UUID deletedBank = UUID.randomUUID();
        UUID usdBank = UUID.randomUUID();
        UUID deletedPortfolioBank = UUID.randomUUID();
        insertBank(tryBank, PORTFOLIO_TRY, "TRY bank", null);
        insertBank(deletedBank, PORTFOLIO_TRY, "Deleted bank", java.time.OffsetDateTime.now());
        insertBank(usdBank, PORTFOLIO_USD, "USD bank", null);
        insertBank(deletedPortfolioBank, PORTFOLIO_DELETED, "Deleted portfolio bank", null);

        insertAccount(tryBank, "EUR account", "EUR", null);
        insertAccount(tryBank, "USD account", "USD", null);
        insertAccount(tryBank, "JPY account", "JPY", null);
        insertAccount(tryBank, "GBP account", "GBP", null);
        insertAccount(tryBank, "TRY account", "TRY", null);
        insertAccount(deletedBank, "CHF account", "CHF", null);
        insertAccount(usdBank, "EUR account", "EUR", null);
        insertAccount(deletedPortfolioBank, "USD account", "USD", null);
    }

    @Test
    void writesRatesForBankAccountCurrenciesOfTryPortfolios() {
        CurrencyRateImportOutcome outcome = importService.importRates(RATE_DATE);

        assertThat(outcome.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(outcome.rateDate()).isEqualTo(RATE_DATE);
        assertThat(outcome.xmlCurrencyCount()).isEqualTo(3);
        assertThat(outcome.portfolioCount()).isEqualTo(1);
        assertThat(outcome.requiredCurrencyCount()).isEqualTo(4);
        assertThat(outcome.writtenRows()).isEqualTo(3);
        assertThat(outcome.existingRows()).isZero();
        assertThat(outcome.missingCurrencies()).containsExactly("GBP");

        Map<String, BigDecimal> rates = rates(PORTFOLIO_TRY);
        assertThat(rates).containsOnlyKeys("EUR", "USD", "JPY");
        assertThat(rates.get("EUR")).isEqualByComparingTo("55.8603");
        assertThat(rates.get("USD")).isEqualByComparingTo("49.9824");
        assertThat(rates.get("JPY")).isEqualByComparingTo("0.31519");

        assertThat(rates(PORTFOLIO_USD)).isEmpty();
        assertThat(rates(PORTFOLIO_DELETED)).isEmpty();
        assertThat(count("SELECT count(*) FROM currency_rate")).isEqualTo(3);
    }

    @Test
    void rerunLeavesExistingRatesUntouched() {
        importService.importRates(RATE_DATE);
        UUID firstId = jdbcTemplate.queryForObject(
                "SELECT id FROM currency_rate WHERE portfolio_id = ? AND currency = 'USD'",
                UUID.class, PORTFOLIO_TRY);

        CurrencyRateImportOutcome second = importService.importRates(RATE_DATE);
        UUID secondId = jdbcTemplate.queryForObject(
                "SELECT id FROM currency_rate WHERE portfolio_id = ? AND currency = 'USD'",
                UUID.class, PORTFOLIO_TRY);

        assertThat(second.writtenRows()).isZero();
        assertThat(second.existingRows()).isEqualTo(3);
        assertThat(secondId).isEqualTo(firstId);
        assertThat(count("SELECT count(*) FROM currency_rate")).isEqualTo(3);
    }

    @Test
    void recordsUnpublishedBulletinWithoutWritingRates() {
        CurrencyRateImportOutcome outcome = importService.importRates(LocalDate.of(2026, 11, 7));

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
                    Map<String, BigDecimal> rates = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        rates.put(rs.getString("currency"), rs.getBigDecimal("rate"));
                    }
                    return rates;
                },
                portfolioId);
    }

    private void insertBank(UUID id, UUID portfolioId, String name, java.time.OffsetDateTime deletedAt) {
        jdbcTemplate.update("INSERT INTO bank VALUES (?, ?, ?, ?)", id, portfolioId, name, deletedAt);
    }

    private void insertAccount(UUID bankId, String name, String currency, java.time.OffsetDateTime deletedAt) {
        jdbcTemplate.update("INSERT INTO bank_account VALUES (?, ?, ?, ?, true, ?)",
                UUID.randomUUID(), bankId, name, currency, deletedAt);
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/kurlar/", CurrencyRateImportIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().endsWith("/202611/06112026.xml")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, XML.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(XML);
        }
    }

    private static byte[] readFixture() {
        try (InputStream in = CurrencyRateImportIntegrationTest.class
                .getResourceAsStream("/tcmb-rates-sample.xml")) {
            return Objects.requireNonNull(in, "Missing test fixture /tcmb-rates-sample.xml").readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
