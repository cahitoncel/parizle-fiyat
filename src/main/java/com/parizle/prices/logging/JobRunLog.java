package com.parizle.prices.logging;

import java.time.Duration;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Marks the current thread as a scheduled job run so every log line is routed to
 * {@code logs/jobs/{date}/{job}.log}. Jobs run once a day, so the date and job name identify a run.
 */
public final class JobRunLog implements AutoCloseable {

    static final String MDC_KEY = "jobRun";

    private static final Logger log = LoggerFactory.getLogger(JobRunLog.class);

    private final String jobName;
    private final long startedAt;

    private JobRunLog(String jobName) {
        this.jobName = jobName;
        this.startedAt = System.nanoTime();
    }

    public static JobRunLog start(String jobName, LocalDate runDate) {
        MDC.put(MDC_KEY, runDate + "/" + jobName);
        JobRunLog run = new JobRunLog(jobName);
        log.info("{} started", jobName);
        return run;
    }

    @Override
    public void close() {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.info("{} finished in {} ms", jobName, elapsedMs);
        MDC.remove(MDC_KEY);
    }
}
