package com.parizle.prices.london;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.bulletin.ImportOutcome;
import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.bulletin.service.PriceUniverse;
import com.parizle.prices.yahoo.YahooFeed;
import com.parizle.prices.yahoo.YahooPriceImportService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
        "spring.datasource.password=test"
})
@Testcontainers
class LondonPriceImportIntegrationTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 6);
    private static final byte[] VOD_JSON = readFixture("/yahoo-chart-vod.json");
    private static final byte[] BATS_JSON = batsJson();
    private static final byte[] HOLIDAY_JSON = """
            {"chart":{"result":[{"meta":{"currency":"GBp","symbol":"HOLIDAY.L"},
            "timestamp":[],"indicators":{"quote":[{}]}}],"error":null}}
            """.getBytes(StandardCharsets.UTF_8);

    private static final Map<String, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();
    private static final HttpServer SERVER = startServer();
    private static final YahooFeed FEED = new YahooFeed(
            "http://127.0.0.1:" + SERVER.getAddress().getPort(),
            "parizle-prices-test",
            ZoneId.of("Europe/London"),
            Duration.ofSeconds(30));

    private static final UUID PORTFOLIO_A = UUID.fromString("019b76da-a800-7001-8000-000000000031");
    private static final UUID PORTFOLIO_B = UUID.fromString("019b76da-a800-7001-8000-000000000032");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private YahooPriceImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void setUpDatabase() {
        REQUESTS.clear();
        jdbcTemplate.execute("DROP TABLE IF EXISTS asset_price_history");
        jdbcTemplate.execute("DROP TABLE IF EXISTS asset");
        jdbcTemplate.execute("DROP TABLE IF EXISTS currency");

        jdbcTemplate.execute("CREATE TABLE currency (code char(3) PRIMARY KEY)");
        jdbcTemplate.execute("INSERT INTO currency (code) VALUES ('GBP'), ('TRY'), ('USD')");
        jdbcTemplate.execute("""
                CREATE TABLE asset (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    portfolio_id uuid NOT NULL,
                    asset_type varchar(20) NOT NULL,
                    symbol varchar(20) NOT NULL,
                    currency char(3) NOT NULL REFERENCES currency(code),
                    market char(4),
                    is_active boolean NOT NULL DEFAULT true,
                    deleted_at timestamptz
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE asset_price_history (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    asset_id uuid NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
                    price_date date NOT NULL,
                    close_price numeric(21, 8) NOT NULL CHECK (close_price > 0),
                    open_price numeric(21, 8),
                    high_price numeric(21, 8),
                    low_price numeric(21, 8),
                    volume numeric(21, 8),
                    currency char(3) NOT NULL REFERENCES currency(code),
                    source varchar(20) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz,
                    deleted_at timestamptz,
                    deleted_by uuid,
                    delete_kind text,
                    CONSTRAINT asset_price_history_ohlc_chk
                        CHECK (high_price IS NULL OR low_price IS NULL OR high_price >= low_price),
                    CONSTRAINT asset_price_history_delete_kind_chk
                        CHECK (delete_kind IS NULL OR (delete_kind IN ('DELETED', 'CORRECTED')
                            AND deleted_at IS NOT NULL))
                )
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX asset_price_history_asset_date_uk
                    ON asset_price_history (asset_id, price_date) WHERE deleted_at IS NULL
                """);

        insertAsset(PORTFOLIO_A, "STOCK", "VOD", "GBP", "XLON", true, false);
        insertAsset(PORTFOLIO_B, "STOCK", "VOD", "GBP", "XLON", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "VOD.L", "GBP", "XLON", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "BATS.L", "GBP", "XLON", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "MISSING", "GBP", "XLON", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "HOLIDAY", "GBP", "XLON", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "BATS", "GBP", "XIST", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "INACTIVE", "GBP", "XLON", false, false);
        insertAsset(PORTFOLIO_A, "STOCK", "DELETED", "GBP", "XLON", true, true);
    }

    @Test
    void importsLondonPricesIntoAssetPriceHistory() {
        ImportOutcome outcome = importService.importPrices(PriceUniverse.LONDON_STOCK, TRADE_DATE, FEED);

        assertThat(outcome.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(outcome.totalRows()).isEqualTo(2);
        assertThat(outcome.writtenRows()).isEqualTo(4);
        assertThat(outcome.skippedRows()).isZero();
        assertThat(outcome.unmatchedRows()).isEqualTo(2);
        assertThat(outcome.existingRows()).isZero();

        Map<String, Object> vod = livePrice(PORTFOLIO_A, "VOD");
        assertThat(vod.get("price_date")).isEqualTo(java.sql.Date.valueOf(TRADE_DATE));
        assertThat((BigDecimal) vod.get("open_price")).isEqualByComparingTo("1.002");
        assertThat((BigDecimal) vod.get("high_price")).isEqualByComparingTo("1.034");
        assertThat((BigDecimal) vod.get("low_price")).isEqualByComparingTo("0.998");
        assertThat((BigDecimal) vod.get("close_price")).isEqualByComparingTo("1.0225");
        assertThat((BigDecimal) vod.get("volume")).isEqualByComparingTo("22222222");
        assertThat(vod.get("currency")).isEqualTo("GBP");
        assertThat(vod.get("source")).isEqualTo("AUTOMATIC");
        assertThat(vod.get("id")).isNotNull();

        assertThat((BigDecimal) livePrice(PORTFOLIO_B, "VOD").get("close_price"))
                .isEqualByComparingTo("1.0225");
        assertThat((BigDecimal) livePrice(PORTFOLIO_A, "VOD.L").get("close_price"))
                .isEqualByComparingTo("1.0225");

        Map<String, Object> bats = livePrice(PORTFOLIO_A, "BATS.L");
        assertThat((BigDecimal) bats.get("close_price")).isEqualByComparingTo("30.5");
        assertThat((BigDecimal) bats.get("volume")).isEqualByComparingTo("5000");

        assertThat(livePrices(PORTFOLIO_A, "MISSING")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "HOLIDAY")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "BATS")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "INACTIVE")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "DELETED")).isEmpty();
        assertThat(count("SELECT count(*) FROM asset_price_history WHERE deleted_at IS NULL")).isEqualTo(4);

        assertThat(requests("VOD.L")).isEqualTo(1);
    }

    @Test
    void rerunLeavesExistingRowsUntouched() {
        importService.importPrices(PriceUniverse.LONDON_STOCK, TRADE_DATE, FEED);
        UUID firstId = (UUID) livePrice(PORTFOLIO_A, "VOD").get("id");

        ImportOutcome second = importService.importPrices(PriceUniverse.LONDON_STOCK, TRADE_DATE, FEED);
        UUID secondId = (UUID) livePrice(PORTFOLIO_A, "VOD").get("id");

        assertThat(second.writtenRows()).isZero();
        assertThat(second.existingRows()).isEqualTo(4);
        assertThat(secondId).isEqualTo(firstId);
        assertThat(count("SELECT count(*) FROM asset_price_history")).isEqualTo(4);
    }

    @Test
    void recordsNotPublishedWhenNoSymbolHasABarForTheDate() {
        ImportOutcome outcome = importService.importPrices(
                PriceUniverse.LONDON_STOCK, LocalDate.of(2026, 11, 7), FEED);

        assertThat(outcome.status()).isEqualTo(ImportStatus.NOT_PUBLISHED);
        assertThat(outcome.writtenRows()).isZero();
        assertThat(count("SELECT count(*) FROM asset_price_history")).isZero();
    }

    private void insertAsset(UUID portfolioId, String assetType, String symbol, String currency,
                             String market, boolean active, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO asset (portfolio_id, asset_type, symbol, currency, market, is_active, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, portfolioId, assetType, symbol, currency, market, active,
                deleted ? new Timestamp(System.currentTimeMillis()) : null);
    }

    private Map<String, Object> livePrice(UUID portfolioId, String symbol) {
        return jdbcTemplate.queryForMap("""
                SELECT p.id, p.price_date, p.close_price, p.open_price, p.high_price, p.low_price,
                       p.volume, p.currency, p.source
                FROM asset_price_history p
                JOIN asset a ON a.id = p.asset_id
                WHERE a.portfolio_id = ? AND a.symbol = ? AND p.deleted_at IS NULL
                """, portfolioId, symbol);
    }

    private List<Map<String, Object>> livePrices(UUID portfolioId, String symbol) {
        return jdbcTemplate.queryForList("""
                SELECT p.id
                FROM asset_price_history p
                JOIN asset a ON a.id = p.asset_id
                WHERE a.portfolio_id = ? AND a.symbol = ? AND p.deleted_at IS NULL
                """, portfolioId, symbol);
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static int requests(String symbol) {
        AtomicInteger count = REQUESTS.get(symbol);
        return count == null ? 0 : count.get();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v8/finance/chart/", LondonPriceImportIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String symbol = exchange.getRequestURI().getPath().substring("/v8/finance/chart/".length());
        REQUESTS.computeIfAbsent(symbol, ignored -> new AtomicInteger()).incrementAndGet();

        byte[] body = switch (symbol) {
            case "VOD.L" -> VOD_JSON;
            case "BATS.L" -> BATS_JSON;
            case "HOLIDAY.L" -> HOLIDAY_JSON;
            default -> null;
        };
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

    private static byte[] batsJson() {
        return """
                {"chart":{"result":[{"meta":{"currency":"GBP","symbol":"BATS.L"},
                "timestamp":[1793952000],"indicators":{"quote":[{
                "open":[30.1],"high":[31.0],"low":[29.9],"close":[30.5],"volume":[5000]}]}}],"error":null}}
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readFixture(String resource) {
        try (InputStream in = LondonPriceImportIntegrationTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
