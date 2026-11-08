package com.parizle.prices.hongkong;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.bulletin.ImportOutcome;
import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.bulletin.service.PriceUniverse;
import com.parizle.prices.yahoo.YahooFeed;
import com.parizle.prices.yahoo.YahooPriceImportService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
class HongKongPriceImportIntegrationTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 6);
    private static final byte[] TENCENT_JSON =
            quoteJson("0700.HK", "650.5", "645.0", "655.0", "642.5", 20000000);
    private static final byte[] HSBC_JSON =
            quoteJson("0005.HK", "78.4", "77.9", "79.0", "77.5", 15000000);
    private static final byte[] ALIBABA_JSON =
            quoteJson("9988.HK", "142.9", "141.0", "144.2", "140.5", 30000000);

    private static final Map<String, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();
    private static final HttpServer SERVER = startServer();
    private static final YahooFeed FEED = new YahooFeed(
            "http://127.0.0.1:" + SERVER.getAddress().getPort(),
            "parizle-prices-test",
            ZoneId.of("Asia/Hong_Kong"),
            Duration.ofSeconds(30));

    private static final UUID PORTFOLIO_A = UUID.fromString("019b76da-a800-7001-8000-000000000051");
    private static final UUID PORTFOLIO_B = UUID.fromString("019b76da-a800-7001-8000-000000000052");

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
        jdbcTemplate.execute("INSERT INTO currency (code) VALUES ('HKD'), ('GBP'), ('USD')");
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

        insertAsset(PORTFOLIO_A, "STOCK", "700", "HKD", "XHKG", true, false);
        insertAsset(PORTFOLIO_B, "STOCK", "0700", "HKD", "XHKG", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "0700.HK", "HKD", "XHKG", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "0005", "HKD", "XHKG", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "9988", "HKD", "XHKG", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "NOPE", "HKD", "XHKG", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "VOD", "GBP", "XLON", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "AAPL", "USD", "XNYS", true, false);
        insertAsset(PORTFOLIO_A, "STOCK", "DEAD", "HKD", "XHKG", false, false);
        insertAsset(PORTFOLIO_A, "STOCK", "GONE", "HKD", "XHKG", true, true);
    }

    @Test
    void importsHongKongPricesIntoAssetPriceHistory() {
        ImportOutcome outcome = importService.importPrices(PriceUniverse.HONG_KONG_STOCK, TRADE_DATE, FEED);

        assertThat(outcome.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(outcome.totalRows()).isEqualTo(3);
        assertThat(outcome.writtenRows()).isEqualTo(5);
        assertThat(outcome.skippedRows()).isZero();
        assertThat(outcome.unmatchedRows()).isEqualTo(1);
        assertThat(outcome.existingRows()).isZero();

        Map<String, Object> tencent = livePrice(PORTFOLIO_A, "700");
        assertThat(tencent.get("price_date")).isEqualTo(java.sql.Date.valueOf(TRADE_DATE));
        assertThat((BigDecimal) tencent.get("open_price")).isEqualByComparingTo("645.0");
        assertThat((BigDecimal) tencent.get("high_price")).isEqualByComparingTo("655.0");
        assertThat((BigDecimal) tencent.get("low_price")).isEqualByComparingTo("642.5");
        assertThat((BigDecimal) tencent.get("close_price")).isEqualByComparingTo("650.5");
        assertThat((BigDecimal) tencent.get("volume")).isEqualByComparingTo("20000000");
        assertThat(tencent.get("currency")).isEqualTo("HKD");
        assertThat(tencent.get("source")).isEqualTo("AUTOMATIC");

        assertThat((BigDecimal) livePrice(PORTFOLIO_B, "0700").get("close_price"))
                .isEqualByComparingTo("650.5");
        assertThat((BigDecimal) livePrice(PORTFOLIO_A, "0700.HK").get("close_price"))
                .isEqualByComparingTo("650.5");
        assertThat((BigDecimal) livePrice(PORTFOLIO_A, "0005").get("close_price"))
                .isEqualByComparingTo("78.4");
        assertThat((BigDecimal) livePrice(PORTFOLIO_A, "9988").get("close_price"))
                .isEqualByComparingTo("142.9");

        assertThat(livePrices(PORTFOLIO_A, "NOPE")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "VOD")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "AAPL")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "DEAD")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "GONE")).isEmpty();
        assertThat(count("SELECT count(*) FROM asset_price_history WHERE deleted_at IS NULL")).isEqualTo(5);

        assertThat(requests("0700.HK")).isEqualTo(1);
    }

    @Test
    void rerunLeavesExistingRowsUntouched() {
        importService.importPrices(PriceUniverse.HONG_KONG_STOCK, TRADE_DATE, FEED);
        UUID firstId = (UUID) livePrice(PORTFOLIO_A, "700").get("id");

        ImportOutcome second = importService.importPrices(
                PriceUniverse.HONG_KONG_STOCK, TRADE_DATE, FEED);
        UUID secondId = (UUID) livePrice(PORTFOLIO_A, "700").get("id");

        assertThat(second.writtenRows()).isZero();
        assertThat(second.existingRows()).isEqualTo(5);
        assertThat(secondId).isEqualTo(firstId);
        assertThat(count("SELECT count(*) FROM asset_price_history")).isEqualTo(5);
    }

    @Test
    void recordsNotPublishedWhenNoSymbolHasABarForTheDate() {
        ImportOutcome outcome = importService.importPrices(
                PriceUniverse.HONG_KONG_STOCK, LocalDate.of(2026, 11, 7), FEED);

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
            server.createContext("/v8/finance/chart/", HongKongPriceImportIntegrationTest::handle);
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
            case "0700.HK" -> TENCENT_JSON;
            case "0005.HK" -> HSBC_JSON;
            case "9988.HK" -> ALIBABA_JSON;
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

    private static byte[] quoteJson(String symbol, String close, String open, String high,
                                    String low, long volume) {
        return """
                {"chart":{"result":[{"meta":{"currency":"HKD","symbol":"%s"},
                "timestamp":[1793928600],"indicators":{"quote":[{
                "open":[%s],"high":[%s],"low":[%s],"close":[%s],"volume":[%d]}]}}],"error":null}}
                """.formatted(symbol, open, high, low, close, volume)
                .getBytes(StandardCharsets.UTF_8);
    }
}
