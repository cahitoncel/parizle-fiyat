package com.parizle.prices.bulletin;

import com.parizle.prices.bulletin.domain.ImportStatus;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retries a bulletin import until it succeeds or its publication retry window is exhausted.
 * Parse errors are treated as permanent because retrying the same malformed file cannot help.
 */
@Component
public class BulletinImportRetry {

    private static final Logger log = LoggerFactory.getLogger(BulletinImportRetry.class);

    public void run(String label, int maxAttempts, Duration retryDelay, Supplier<ImportOutcome> importCall) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ImportOutcome outcome = importCall.get();
                if (outcome.status() != ImportStatus.NOT_PUBLISHED) {
                    return;
                }
                log.warn("{} is not published yet (attempt {}/{})", label, attempt, maxAttempts);
            } catch (BulletinImportException e) {
                if (e.getCause() instanceof BulletinParseException) {
                    log.error("{} failed permanently", label, e);
                    return;
                }
                log.warn("{} failed (attempt {}/{}): {}", label, attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    log.error("Giving up on {}", label, e);
                    return;
                }
            }

            if (attempt < maxAttempts && !sleepBeforeRetry(retryDelay)) {
                return;
            }
        }
        log.warn("{} was not published after {} attempts", label, maxAttempts);
    }

    private static boolean sleepBeforeRetry(Duration retryDelay) {
        try {
            Thread.sleep(retryDelay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Bulletin import interrupted; stopping retries");
            return false;
        }
    }
}
