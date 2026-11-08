package com.parizle.prices.bulletin;

import com.parizle.prices.config.BistBulletinProperties;
import com.parizle.prices.logging.JobRunLog;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bist.bulletin", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class BulletinImportJob {

    private final BulletinImportService importService;
    private final BulletinImportRetry importRetry;
    private final BistBulletinProperties properties;

    public BulletinImportJob(BulletinImportService importService,
                             BulletinImportRetry importRetry,
                             BistBulletinProperties properties) {
        this.importService = importService;
        this.importRetry = importRetry;
        this.properties = properties;
    }

    @Scheduled(cron = "${bist.bulletin.cron}", zone = "${bist.bulletin.timezone}")
    public void importDailyBulletin() {
        int session = properties.session();
        LocalDate tradeDate = LocalDate.now(properties.timezone());
        try (JobRunLog ignored = JobRunLog.start("bist-bulletin", tradeDate)) {
            importRetry.run(
                    "Bulletin for " + tradeDate + " session " + session,
                    properties.maxAttempts(),
                    properties.retryDelay(),
                    () -> importService.importBulletin(tradeDate, session));
        }
    }
}
