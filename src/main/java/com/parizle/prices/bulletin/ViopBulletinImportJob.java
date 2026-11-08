package com.parizle.prices.bulletin;

import com.parizle.prices.config.BistViopProperties;
import com.parizle.prices.logging.JobRunLog;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bist.viop", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class ViopBulletinImportJob {

    private final ViopBulletinImportService importService;
    private final BulletinImportRetry importRetry;
    private final BistViopProperties properties;

    public ViopBulletinImportJob(ViopBulletinImportService importService,
                                 BulletinImportRetry importRetry,
                                 BistViopProperties properties) {
        this.importService = importService;
        this.importRetry = importRetry;
        this.properties = properties;
    }

    @Scheduled(cron = "${bist.viop.cron}", zone = "${bist.viop.timezone}")
    public void importDailyViopBulletin() {
        LocalDate tradeDate = LocalDate.now(properties.timezone());
        try (JobRunLog ignored = JobRunLog.start("viop-bulletin", tradeDate)) {
            importRetry.run(
                    "VIOP bulletin for " + tradeDate,
                    properties.maxAttempts(),
                    properties.retryDelay(),
                    () -> importService.importBulletin(tradeDate));
        }
    }
}
