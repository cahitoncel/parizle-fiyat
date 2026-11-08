package com.parizle.prices.yahoo;

import com.parizle.prices.bulletin.service.PriceUniverse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lists the distinct Yahoo symbols needed to price active assets of one {@link PriceUniverse}.
 */
@Component
public class YahooAssetCatalog {

    private final JdbcTemplate jdbcTemplate;

    public YahooAssetCatalog(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> activeYahooSymbols(PriceUniverse universe) {
        String placeholders = String.join(", ", Collections.nCopies(universe.markets().size(), "?"));
        String marketFilter = universe.includesNullMarket()
                ? "(market IS NULL OR market IN (" + placeholders + "))"
                : "market IN (" + placeholders + ")";
        String sql = """
                SELECT DISTINCT symbol
                FROM asset
                WHERE is_active = true
                  AND deleted_at IS NULL
                  AND asset_type = ?
                  AND %s
                ORDER BY symbol
                """.formatted(marketFilter);

        List<Object> parameters = new ArrayList<>();
        parameters.add(universe.assetType().name());
        parameters.addAll(universe.markets());

        List<String> symbols = jdbcTemplate.queryForList(sql, String.class, parameters.toArray());

        return symbols.stream()
                .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                .filter(symbol -> !symbol.isEmpty())
                .map(universe::toFeedSymbol)
                .distinct()
                .toList();
    }
}
