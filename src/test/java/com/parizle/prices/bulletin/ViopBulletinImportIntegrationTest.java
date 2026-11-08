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
import java.nio.charset.StandardCharsets;
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
        "bist.viop.max-attempts=1",
        "bist.viop.retry-delay=1s",
        "spring.datasource.password=test"
})
@Testcontainers
class ViopBulletinImportIntegrationTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 6);
    private static final LocalDate ZIP_TRADE_DATE = LocalDate.of(2026, 11, 5);
    private static final byte[] CSV = readFixture();
    private static final byte[] ZIPPED_CSV = buildArchive(CSV, ZIP_TRADE_DATE);
    private static final HttpServer SERVER = startServer();
    private static final String BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();

    private static final UUID PORTFOLIO_A = UUID.fromString("019b76da-a800-7001-8000-000000000011");
    private static final UUID PORTFOLIO_B = UUID.fromString("019b76da-a800-7001-8000-000000000012");
    private static final UUID PORTFOLIO_C = UUID.fromString("019b76da-a800-7001-8000-000000000013");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void bulletinProperties(DynamicPropertyRegistry registry) {
        registry.add("bist.bulletin.base-url", () -> BASE_URL);
    }

    @Autowired
    private ViopBulletinImportService importService;

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

        insertAsset(PORTFOLIO_A, "FUTURE", "AEFES0127", null);
        insertAsset(PORTFOLIO_B, "FUTURE", "F_AEFES0127", null);
        insertAsset(PORTFOLIO_A, "FUTURE", "USDTRYKE0127C52000", null);
        insertAsset(PORTFOLIO_A, "FUTURE", "XU0301226", null);
        insertAsset(PORTFOLIO_C, "FUTURE", "AEFES0127", "XNYS");
        insertAsset(PORTFOLIO_A, "STOCK", "TCELLE1226P94.00", "XIST");
    }

    @Test
    void importsViopBulletinIntoAssetPriceHistory() {
        ImportOutcome outcome = importService.importBulletin(TRADE_DATE);

        assertThat(outcome.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(outcome.totalRows()).isEqualTo(3);
        assertThat(outcome.writtenRows()).isEqualTo(3);
        assertThat(outcome.skippedRows()).isZero();
        assertThat(outcome.unmatchedRows()).isEqualTo(1);
        assertThat(outcome.existingRows()).isZero();

        Map<String, Object> future = livePrice(PORTFOLIO_A, "AEFES0127");
        assertThat(future.get("price_date")).isEqualTo(java.sql.Date.valueOf(TRADE_DATE));
        assertThat((BigDecimal) future.get("close_price")).isEqualByComparingTo("18.35");
        assertThat((BigDecimal) future.get("open_price")).isEqualByComparingTo("17.56");
        assertThat((BigDecimal) future.get("high_price")).isEqualByComparingTo("18.35");
        assertThat((BigDecimal) future.get("low_price")).isEqualByComparingTo("17.22");
        assertThat((BigDecimal) future.get("volume")).isEqualByComparingTo("287");
        assertThat(future.get("currency")).isEqualTo("TRY");
        assertThat(future.get("source")).isEqualTo("AUTOMATIC");
        assertThat(future.get("id")).isNotNull();

        assertThat((BigDecimal) livePrice(PORTFOLIO_B, "F_AEFES0127").get("close_price"))
                .isEqualByComparingTo("18.35");

        Map<String, Object> option = livePrice(PORTFOLIO_A, "USDTRYKE0127C52000");
        assertThat((BigDecimal) option.get("close_price")).isEqualByComparingTo("1508.3");
        assertThat((BigDecimal) option.get("volume")).isEqualByComparingTo("0");

        assertThat(livePrices(PORTFOLIO_A, "XU0301226")).isEmpty();
        assertThat(livePrices(PORTFOLIO_C, "AEFES0127")).isEmpty();
        assertThat(livePrices(PORTFOLIO_A, "TCELLE1226P94.00")).isEmpty();
    }

    @Test
    void rerunLeavesExistingRowsUntouched() {
        importService.importBulletin(TRADE_DATE);
        UUID firstId = (UUID) livePrice(PORTFOLIO_A, "AEFES0127").get("id");

        ImportOutcome second = importService.importBulletin(TRADE_DATE);
        UUID secondId = (UUID) livePrice(PORTFOLIO_A, "AEFES0127").get("id");

        assertThat(second.writtenRows()).isZero();
        assertThat(second.existingRows()).isEqualTo(3);
        assertThat(secondId).isEqualTo(firstId);
        assertThat(count("SELECT count(*) FROM asset_price_history WHERE deleted_at IS NULL")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM asset_price_history")).isEqualTo(3);
    }

    @Test
    void importsZippedViopBulletin() {
        ImportOutcome outcome = importService.importBulletin(ZIP_TRADE_DATE);

        assertThat(outcome.status()).isEqualTo(ImportStatus.IMPORTED);
        assertThat(outcome.totalRows()).isEqualTo(3);
        assertThat(outcome.writtenRows()).isEqualTo(3);
        assertThat((BigDecimal) livePrice(PORTFOLIO_A, "AEFES0127").get("close_price"))
                .isEqualByComparingTo("18.35");
    }

    @Test
    void recordsUnpublishedBulletinWithoutWritingPrices() {
        ImportOutcome outcome = importService.importBulletin(LocalDate.of(2026, 11, 7));

        assertThat(outcome.status()).isEqualTo(ImportStatus.NOT_PUBLISHED);
        assertThat(outcome.writtenRows()).isZero();
        assertThat(count("SELECT count(*) FROM asset_price_history")).isZero();
    }

    private void insertAsset(UUID portfolioId, String assetType, String symbol, String market) {
        jdbcTemplate.update("""
                INSERT INTO asset (portfolio_id, asset_type, symbol, currency, market)
                VALUES (?, ?, ?, 'TRY', ?)
                """, portfolioId, assetType, symbol, market);
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
            server.createContext("/data/vadeli/", ViopBulletinImportIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body;
        String contentType;
        if (path.endsWith("/viop_20261106.csv")) {
            body = CSV;
            contentType = "text/csv";
        } else if (path.endsWith("/viop_20261105.csv")) {
            body = ZIPPED_CSV;
            contentType = "application/zip";
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", contentType);
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] readFixture() {
        try (InputStream in = ViopBulletinImportIntegrationTest.class.getResourceAsStream("/viop-sample.csv")) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture /viop-sample.csv");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] buildArchive(byte[] csv, LocalDate tradeDate) {
        byte[] datedCsv = new String(csv, StandardCharsets.UTF_8)
                .replace("2026-11-06", tradeDate.toString())
                .getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("viop_" + tradeDate + ".csv"));
            zip.write(datedCsv);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }
}
