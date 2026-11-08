package com.parizle.prices.frankfurter;

import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.config.FrankfurterRateProperties;
import com.parizle.prices.logging.JobRunLog;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Imports daily reference rates after the ECB publication, using each portfolio's currency as base.
 */
@Component
@ConditionalOnProperty(prefix = "frankfurter.rates", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class FrankfurterRateImportJob {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterRateImportJob.class);

    private final FrankfurterRateImportService importService;
    private final FrankfurterRateProperties properties;

    public FrankfurterRateImportJob(FrankfurterRateImportService importService,
                                    FrankfurterRateProperties properties) {
        this.importService = importService;
        this.properties = properties;
    }

    @Scheduled(cron = "${frankfurter.rates.cron}", zone = "${frankfurter.rates.timezone}")
    public void importDailyRates() {
        LocalDate rateDate = LocalDate.now(properties.timezone());
        try (JobRunLog ignored = JobRunLog.start("frankfurter-rates", rateDate)) {
            int maxAttempts = properties.maxAttempts();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    FrankfurterRateImportOutcome outcome = importService.importRates(rateDate);
                    if (outcome.status() == ImportStatus.IMPORTED) {
                        return;
                    }
                    log.warn("Frankfurter rates for {} are not available yet (attempt {}/{})",
                            rateDate, attempt, maxAttempts);
                } catch (RuntimeException e) {
                    if (attempt == maxAttempts) {
                        log.error("Giving up on Frankfurter rates for {}", rateDate, e);
                        return;
                    }
                    log.warn("Frankfurter rate import for {} failed (attempt {}/{}): {}",
                            rateDate, attempt, maxAttempts, e.getMessage());
                }

                if (attempt < maxAttempts && !sleepBeforeRetry()) {
                    return;
                }
            }
            log.warn("Frankfurter rates for {} were not available after {} attempts", rateDate, maxAttempts);
        }
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(properties.retryDelay().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Frankfurter rate import interrupted; stopping retries");
            return false;
        }
    }
}
