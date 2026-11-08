package com.parizle.prices.bulletin;

import static org.assertj.core.api.Assertions.assertThat;

import com.parizle.prices.bulletin.domain.ImportStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
        "bist.bulletin.max-attempts=1",
        "bist.bulletin.retry-delay=1s",
        "spring.datasource.password=test"
})
@Testcontainers
class BulletinImportIntegrationTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 6);
    private static final byte[] ARCHIVE = buildArchive();
    private static final HttpServer SERVER = startServer();
    private static final String BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();

    private static final UUID PORTFOLIO_A = UUID.fromString("019b76da-a800-7001-8000-000000000001");
    private static final UUID PORTFOLIO_B = UUID.fromString("019b76da-a800-7001-8000-000000000002");
    private static final UUID PORTFOLIO_C = UUID.fromString("019b76da-a800-7001-8000-000000000003");
    private static final UUID PORTFOLIO_D = UUID.fromString("019b76da-a800-7001-8000-000000000004");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void bulletinProperties(DynamicPropertyRegistry registry) {
        registry.add("bist.bulletin.base-url", () -> BASE_URL);
    }

    @Autowired
    private BulletinImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void setUpDatabase() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS asset_price_history");
        jdbcTemplate.execute("DROP TABLE IF EXISTS asset");
        jdbcTemplate.execute("DROP TABLE IF EXISTS currency");

        jdbcTemplate.execute("CREATE TABLE currency (code char(3) PRIMARY KEY)");
        jdbcTemplate.execute("INSERT INTO currency (code) VALUES ('TRY'), ('USD')");
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

        insertAsset(PORTFOLIO_A, "AAGYO", "XIST");
        insertAsset(PORTFOLIO_A, "AEFES", "XIST");
        insertAsset(PORTFOLIO_B, "AEFES", "XIST");
        insertAsset(PORTFOLIO_A, "THYAO", "XIST");
        insertAsset(PORTFOLIO_C, "THYAO", "XNAS");
        insertAsset(PORTFOLIO_A, "LIDER", "XIST");
        insertAsset(PORTFOLIO_A, "TPKGY", "XIST");
        insertAsset(PORTFOLIO_D, "TPKGY.F1", "XIST");
    }

    @Test
    void importsBulletinIntoAssetPriceHistory() {
        ImportOutcome outcome = importService.importBulletin(TRADE_DATE, 1);

        assertThat(outcome.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(outcome.totalRows()).isEqualTo(6);
        assertThat(outcome.writtenRows()).isEqualTo(5);
        assertThat(outcome.skippedRows()).isEqualTo(1);
        assertThat(outcome.unmatchedRows()).isEqualTo(1);
        assertThat(outcome.existingRows()).isZero();

        Map<String, Object> thyao = livePrice(PORTFOLIO_A, "THYAO");
        assertThat(thyao.get("price_date")).isEqualTo(java.sql.Date.valueOf(TRADE_DATE));
        assertThat((BigDecimal) thyao.get("close_price")).isEqualByComparingTo("299.25");
        assertThat((BigDecimal) thyao.get("open_price")).isEqualByComparingTo("290");
        assertThat((BigDecimal) thyao.get("high_price")).isEqualByComparingTo("299.75");
        assertThat((BigDecimal) thyao.get("low_price")).isEqualByComparingTo("288");
        assertThat((BigDecimal) thyao.get("volume")).isEqualByComparingTo("42436087");
        assertThat(thyao.get("currency")).isEqualTo("TRY");
        assertThat(thyao.get("source")).isEqualTo("AUTOMATIC");
        assertThat(thyao.get("id")).isNotNull();
        assertThat(thyao.get("created_at")).isNotNull();

        assertThat(livePrices(PORTFOLIO_A, "AEFES")).hasSize(1);
        assertThat(livePrices(PORTFOLIO_B, "AEFES")).hasSize(1);
        assertThat((BigDecimal) livePrice(PORTFOLIO_A, "AEFES").get("close_price"))
                .isEqualByComparingTo("16.85");
        assertThat(livePrices(PORTFOLIO_C, "THYAO")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "TPKGY")).isEmpty();
        assertThat(livePrices(PORTFOLIO_D, "TPKGY.F1")).isEmpty();
    }

    @Test
    void rerunLeavesExistingRowsUntouched() {
        importService.importBulletin(TRADE_DATE, 1);
        UUID firstId = (UUID) livePrice(PORTFOLIO_A, "THYAO").get("id");

        ImportOutcome second = importService.importBulletin(TRADE_DATE, 1);
        UUID secondId = (UUID) livePrice(PORTFOLIO_A, "THYAO").get("id");

        assertThat(second.writtenRows()).isZero();
        assertThat(second.existingRows()).isEqualTo(5);
        assertThat(secondId).isEqualTo(firstId);
        assertThat(count("SELECT count(*) FROM asset_price_history WHERE deleted_at IS NULL")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM asset_price_history")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM asset_price_history WHERE deleted_at IS NOT NULL")).isZero();
    }

    @Test
    void skipsInstrumentsWithoutMatchingAsset() {
        jdbcTemplate.update("DELETE FROM asset WHERE symbol = 'LIDER'");

        ImportOutcome outcome = importService.importBulletin(TRADE_DATE, 1);

        assertThat(outcome.writtenRows()).isEqualTo(4);
        assertThat(outcome.unmatchedRows()).isEqualTo(1);
    }

    @Test
    void recordsUnpublishedBulletinWithoutWritingPrices() {
        LocalDate weekend = LocalDate.of(2026, 11, 7);

        ImportOutcome outcome = importService.importBulletin(weekend, 1);

        assertThat(outcome.status()).isEqualTo(ImportStatus.NOT_PUBLISHED);
        assertThat(outcome.writtenRows()).isZero();
        assertThat(count("SELECT count(*) FROM asset_price_history")).isZero();
    }

    private void insertAsset(UUID portfolioId, String symbol, String market) {
        jdbcTemplate.update("""
                INSERT INTO asset (portfolio_id, asset_type, symbol, currency, market)
                VALUES (?, 'STOCK', ?, 'TRY', ?)
                """, portfolioId, symbol, market);
    }

    private Map<String, Object> livePrice(UUID portfolioId, String symbol) {
        return jdbcTemplate.queryForMap("""
                SELECT p.id, p.price_date, p.close_price, p.open_price, p.high_price, p.low_price,
                       p.volume, p.currency, p.source, p.created_at
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

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/data/thb/", BulletinImportIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.endsWith("/thb202611061.zip")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "application/zip");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, ARCHIVE.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(ARCHIVE);
        }
    }

    private static byte[] buildArchive() {
        byte[] csv;
        try (InputStream in = BulletinImportIntegrationTest.class.getResourceAsStream("/bulletin-sample.csv")) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture /bulletin-sample.csv");
            }
            csv = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("thb202611061.csv"));
            zip.write(csv);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }
}
