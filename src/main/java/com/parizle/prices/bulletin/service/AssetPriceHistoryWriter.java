package com.parizle.prices.bulletin.service;

import com.parizle.prices.bulletin.BulletinParseException;
import com.parizle.prices.bulletin.domain.AssetType;
import com.parizle.prices.bulletin.domain.MarketPrice;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes parsed bulletin prices into the existing {@code asset_price_history} table.
 *
 * <p>Only active, non-deleted assets of the requested {@link PriceUniverse} are considered. Each
 * feed row is read once and then written for every asset id sharing that symbol, because assets are
 * portfolio-scoped.
 *
 * <p>Existing live rows for the same {@code (asset_id, price_date)} are left untouched: if a row
 * already exists, that asset is skipped instead of being replaced or updated.
 */
@Component
public class AssetPriceHistoryWriter {

    static final String SOURCE = "AUTOMATIC";

    private static final Logger log = LoggerFactory.getLogger(AssetPriceHistoryWriter.class);

    private final JdbcTemplate jdbcTemplate;

    public AssetPriceHistoryWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public PriceWriteResult write(LocalDate tradeDate, AssetType assetType, List<MarketPrice> prices) {
        return write(tradeDate, PriceUniverse.forAssetType(assetType), prices);
    }

    @Transactional
    public PriceWriteResult write(LocalDate tradeDate, PriceUniverse universe, List<MarketPrice> prices) {
        Map<String, MarketPrice> pricesByCode = indexPrices(tradeDate, prices);
        Map<String, List<AssetRef>> assetsBySymbol = loadAssets(universe);
        Map<UUID, Candidate> chosen = new LinkedHashMap<>();
        int skippedRows = 0;
        int unmatchedRows = 0;

        for (Map.Entry<String, List<AssetRef>> entry : assetsBySymbol.entrySet()) {
            String symbol = entry.getKey();
            String bulletinCode = resolveBulletinCode(universe, symbol, pricesByCode);
            List<AssetRef> assets = entry.getValue();

            if (bulletinCode == null) {
                unmatchedRows += assets.size();
                log.debug("No {} price for asset symbol {}", universe, symbol);
                continue;
            }
            MarketPrice price = pricesByCode.get(bulletinCode);
            if (price.close() == null || price.close().signum() <= 0) {
                skippedRows += assets.size();
                log.debug("Skipping {} asset rows for {}: price is not positive",
                        assets.size(), bulletinCode);
                continue;
            }
            for (AssetRef asset : assets) {
                chosen.put(asset.id(), new Candidate(asset, price));
            }
        }

        if (chosen.isEmpty()) {
            log.info("No {} asset_price_history rows written for {}: {} skipped, {} unmatched",
                    universe, tradeDate, skippedRows, unmatchedRows);
            return new PriceWriteResult(0, skippedRows, unmatchedRows, 0);
        }

        Set<UUID> existing = findExistingAssetIds(tradeDate, chosen.keySet());
        if (!existing.isEmpty()) {
            chosen.keySet().removeAll(existing);
        }
        if (chosen.isEmpty()) {
            log.info("All {} matched {} asset_price_history rows already exist for {}",
                    existing.size(), universe, tradeDate);
            return new PriceWriteResult(0, skippedRows, unmatchedRows, existing.size());
        }

        insertPrices(tradeDate, chosen.values());

        log.info("Wrote {} {} asset_price_history rows for {}: {} skipped, {} unmatched, {} already existing",
                chosen.size(), universe, tradeDate, skippedRows, unmatchedRows, existing.size());
        return new PriceWriteResult(chosen.size(), skippedRows, unmatchedRows, existing.size());
    }

    private static Map<String, MarketPrice> indexPrices(LocalDate tradeDate, List<MarketPrice> prices) {
        Map<String, MarketPrice> byCode = new HashMap<>(Math.max(16, prices.size() * 2));
        for (MarketPrice price : prices) {
            if (!tradeDate.equals(price.tradeDate())) {
                throw new BulletinParseException("Bulletin contains trade date " + price.tradeDate()
                        + " but expected " + tradeDate);
            }
            String code = price.instrumentCode().trim().toUpperCase(Locale.ROOT);
            byCode.putIfAbsent(code, price);
        }
        return byCode;
    }

    private static String resolveBulletinCode(PriceUniverse universe, String symbol,
                                              Map<String, MarketPrice> pricesByCode) {
        String code = universe.toFeedSymbol(symbol);
        if (pricesByCode.containsKey(code)) {
            return code;
        }
        for (String prefix : universe.symbolPrefixes()) {
            String candidate = prefix + symbol;
            if (pricesByCode.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, List<AssetRef>> loadAssets(PriceUniverse universe) {
        String placeholders = String.join(", ", Collections.nCopies(universe.markets().size(), "?"));
        String marketFilter = universe.includesNullMarket()
                ? "(market IS NULL OR market IN (" + placeholders + "))"
                : "market IN (" + placeholders + ")";
        String sql = """
                SELECT id, symbol, currency
                FROM asset
                WHERE is_active = true
                  AND deleted_at IS NULL
                  AND asset_type = ?
                  AND %s
                """.formatted(marketFilter);

        List<Object> parameters = new ArrayList<>();
        parameters.add(universe.assetType().name());
        parameters.addAll(universe.markets());

        Map<String, List<AssetRef>> bySymbol = new HashMap<>();
        jdbcTemplate.query(sql,
                rs -> {
                    String symbol = rs.getString("symbol").trim().toUpperCase(Locale.ROOT);
                    AssetRef asset = new AssetRef(
                            rs.getObject("id", UUID.class),
                            symbol,
                            rs.getString("currency").trim());
                    bySymbol.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(asset);
                },
                parameters.toArray());
        return bySymbol;
    }

    private Set<UUID> findExistingAssetIds(LocalDate tradeDate, Set<UUID> assetIds) {
        UUID[] assetArray = assetIds.toArray(UUID[]::new);
        Set<UUID> existing = jdbcTemplate.execute((ConnectionCallback<Set<UUID>>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT asset_id FROM asset_price_history"
                            + " WHERE deleted_at IS NULL AND price_date = ? AND asset_id = ANY(?)")) {
                statement.setObject(1, tradeDate);
                Array array = connection.createArrayOf("uuid", assetArray);
                try {
                    statement.setArray(2, array);
                    try (ResultSet rs = statement.executeQuery()) {
                        Set<UUID> ids = new HashSet<>();
                        while (rs.next()) {
                            ids.add(rs.getObject(1, UUID.class));
                        }
                        return ids;
                    }
                } finally {
                    array.free();
                }
            }
        });
        return existing == null ? Set.of() : existing;
    }

    private void insertPrices(LocalDate tradeDate, Iterable<Candidate> candidates) {
        List<Object[]> batch = new ArrayList<>();
        for (Candidate candidate : candidates) {
            MarketPrice price = candidate.price();
            BigDecimal high = price.high();
            BigDecimal low = price.low();
            if (high != null && low != null && high.compareTo(low) < 0) {
                log.warn("Swapping high/low for {} on {}: high {} < low {}",
                        price.instrumentCode(), tradeDate, high, low);
                BigDecimal swap = high;
                high = low;
                low = swap;
            }
            batch.add(new Object[]{
                    candidate.asset().id(),
                    tradeDate,
                    price.close(),
                    price.open(),
                    high,
                    low,
                    price.volume(),
                    candidate.asset().currency(),
                    SOURCE
            });
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO asset_price_history
                    (asset_id, price_date, close_price, open_price, high_price, low_price, volume, currency, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (asset_id, price_date) WHERE deleted_at IS NULL DO NOTHING
                """, batch);
    }

    private record AssetRef(UUID id, String symbol, String currency) {
    }

    private record Candidate(AssetRef asset, MarketPrice price) {
    }
}
