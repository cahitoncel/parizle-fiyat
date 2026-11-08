package com.parizle.prices.hongkong;

import com.parizle.prices.bulletin.ImportOutcome;
import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.bulletin.service.PriceUniverse;
import com.parizle.prices.config.HongKongPriceProperties;
import com.parizle.prices.logging.JobRunLog;
import com.parizle.prices.yahoo.YahooFeed;
import com.parizle.prices.yahoo.YahooPriceImportService;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Imports daily prices for Hong Kong Stock Exchange assets after the HKEX session closes.
 */
@Component
@ConditionalOnProperty(prefix = "hongkong.prices", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class HongKongPriceImportJob {

    private static final Logger log = LoggerFactory.getLogger(HongKongPriceImportJob.class);

    private final YahooPriceImportService importService;
    private final HongKongPriceProperties properties;

    public HongKongPriceImportJob(YahooPriceImportService importService,
                                  HongKongPriceProperties properties) {
        this.importService = importService;
        this.properties = properties;
    }

    @Scheduled(cron = "${hongkong.prices.cron}", zone = "${hongkong.prices.timezone}")
    public void importDailyPrices() {
        LocalDate tradeDate = LocalDate.now(properties.timezone());
        try (JobRunLog ignored = JobRunLog.start("hongkong-prices", tradeDate)) {
            YahooFeed feed = new YahooFeed(properties.baseUrl(), properties.userAgent(),
                    properties.timezone(), properties.readTimeout());
            int maxAttempts = properties.maxAttempts();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ImportOutcome outcome = importService.importPrices(
                            PriceUniverse.HONG_KONG_STOCK, tradeDate, feed);
                    if (outcome.status() == ImportStatus.IMPORTED) {
                        return;
                    }
                    log.warn("Hong Kong prices for {} are not available yet (attempt {}/{})",
                            tradeDate, attempt, maxAttempts);
                } catch (RuntimeException e) {
                    if (attempt == maxAttempts) {
                        log.error("Giving up on Hong Kong prices for {}", tradeDate, e);
                        return;
                    }
                    log.warn("Hong Kong price import for {} failed (attempt {}/{}): {}",
                            tradeDate, attempt, maxAttempts, e.getMessage());
                }

                if (attempt < maxAttempts && !sleepBeforeRetry()) {
                    return;
                }
            }
            log.warn("Hong Kong prices for {} were not available after {} attempts", tradeDate, maxAttempts);
        }
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(properties.retryDelay().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hong Kong price import interrupted; stopping retries");
            return false;
        }
    }
}
