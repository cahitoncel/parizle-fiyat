package com.parizle.prices.currency;

import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.config.TcmbRateProperties;
import com.parizle.prices.logging.JobRunLog;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "tcmb.rates", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class CurrencyRateImportJob {

    private static final Logger log = LoggerFactory.getLogger(CurrencyRateImportJob.class);

    private final CurrencyRateImportService importService;
    private final TcmbRateProperties properties;

    public CurrencyRateImportJob(CurrencyRateImportService importService, TcmbRateProperties properties) {
        this.importService = importService;
        this.properties = properties;
    }

    @Scheduled(cron = "${tcmb.rates.cron}", zone = "${tcmb.rates.timezone}")
    public void importDailyRates() {
        LocalDate rateDate = LocalDate.now(properties.timezone());
        try (JobRunLog ignored = JobRunLog.start("tcmb-rates", rateDate)) {
            int maxAttempts = properties.maxAttempts();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    CurrencyRateImportOutcome outcome = importService.importRates(rateDate);
                    if (outcome.status() == ImportStatus.IMPORTED) {
                        return;
                    }
                    log.warn("TCMB rates for {} are not published yet (attempt {}/{})",
                            rateDate, attempt, maxAttempts);
                } catch (RuntimeException e) {
                    if (attempt == maxAttempts) {
                        log.error("Giving up on TCMB rates for {}", rateDate, e);
                        return;
                    }
                    log.warn("TCMB rate import for {} failed (attempt {}/{}): {}",
                            rateDate, attempt, maxAttempts, e.getMessage());
                }

                if (attempt < maxAttempts && !sleepBeforeRetry()) {
                    return;
                }
            }
            log.warn("TCMB rates for {} were not published after {} attempts", rateDate, maxAttempts);
        }
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(properties.retryDelay().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("TCMB rate import interrupted; stopping retries");
            return false;
        }
    }
}
