package com.parizle.prices.yahoo;

import com.parizle.prices.bulletin.ImportOutcome;
import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.bulletin.domain.MarketPrice;
import com.parizle.prices.bulletin.service.AssetPriceHistoryWriter;
import com.parizle.prices.bulletin.service.PriceUniverse;
import com.parizle.prices.bulletin.service.PriceWriteResult;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches the daily close for every active asset in a {@link PriceUniverse} and stores it in
 * {@code asset_price_history}.
 *
 * <p>Symbols are fetched once even when several portfolio-scoped assets share them; the writer then
 * inserts one row per matching asset id.
 */
@Service
public class YahooPriceImportService {

    private static final Logger log = LoggerFactory.getLogger(YahooPriceImportService.class);

    private final YahooAssetCatalog assetCatalog;
    private final YahooChartClient client;
    private final AssetPriceHistoryWriter priceWriter;

    public YahooPriceImportService(YahooAssetCatalog assetCatalog,
                                   YahooChartClient client,
                                   AssetPriceHistoryWriter priceWriter) {
        this.assetCatalog = assetCatalog;
        this.client = client;
        this.priceWriter = priceWriter;
    }

    public ImportOutcome importPrices(PriceUniverse universe, LocalDate tradeDate, YahooFeed feed) {
        List<String> symbols = assetCatalog.activeYahooSymbols(universe);
        if (symbols.isEmpty()) {
            log.info("No active {} assets to price for {}", universe, tradeDate);
            return new ImportOutcome(ImportStatus.IMPORTED, tradeDate, 0, 0, 0, 0, 0);
        }

        List<MarketPrice> prices = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try {
            log.info("Importing {} prices for {} ({} symbols)", universe, tradeDate, symbols.size());
            for (String symbol : symbols) {
                Optional<DailyQuote> fetched = client.fetch(feed, symbol, tradeDate);
                if (fetched.isEmpty()) {
                    missing.add(symbol);
                } else {
                    prices.add(toMarketPrice(fetched.get()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YahooChartException("Interrupted while fetching " + universe
                    + " prices for " + tradeDate, e);
        } catch (IOException e) {
            throw new YahooChartException("Failed to fetch " + universe + " prices for " + tradeDate, e);
        }

        if (prices.isEmpty()) {
            log.warn("No {} prices available for {} (missing: {})", universe, tradeDate, missing);
            return new ImportOutcome(ImportStatus.NOT_PUBLISHED, tradeDate, 0, 0, 0, 0, 0);
        }

        PriceWriteResult result = priceWriter.write(tradeDate, universe, prices);
        if (!missing.isEmpty()) {
            log.warn("No {} daily bar on {} for {} symbols: {}",
                    universe, tradeDate, missing.size(), missing);
        }
        log.info("{} prices {}: {} quotes, {} written, {} skipped, {} unmatched, {} already existing",
                universe, tradeDate, prices.size(), result.writtenRows(), result.skippedRows(),
                result.unmatchedRows(), result.existingRows());

        return new ImportOutcome(ImportStatus.IMPORTED, tradeDate, prices.size(),
                result.writtenRows(), result.skippedRows(), result.unmatchedRows(), result.existingRows());
    }

    private static MarketPrice toMarketPrice(DailyQuote quote) {
        return new MarketPrice(
                quote.tradeDate(),
                quote.symbol(),
                quote.close(),
                quote.open(),
                quote.high(),
                quote.low(),
                quote.volume());
    }
}
