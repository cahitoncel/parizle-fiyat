package com.parizle.prices.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.MDC;

class JobRunLogbackConfigTest {

    @TempDir
    Path logDir;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void routesJobEventsToTheDatedFileAndIgnoresEventsOutsideAJob() throws Exception {
        System.setProperty("LOG_DIR", logDir.toString());
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(MDC.getMDCAdapter());
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(getClass().getResource("/logback-spring.xml"));

            Logger logger = context.getLogger("job-test");
            MDC.put(JobRunLog.MDC_KEY, "2026-11-08/london-prices");
            logger.info("inside job");
            logger.warn("problem inside job");
            MDC.remove(JobRunLog.MDC_KEY);
            logger.info("outside job");
            context.stop();

            Path jobLog = logDir.resolve("jobs").resolve("2026-11-08").resolve("london-prices.log");
            assertThat(jobLog).exists();
            assertThat(Files.readString(jobLog))
                    .containsPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
                    .contains("inside job")
                    .contains("problem inside job")
                    .doesNotContain("outside job");

            assertThat(Files.readString(logDir.resolve("problems.log")))
                    .contains("problem inside job")
                    .doesNotContain("INFO");
        } finally {
            context.stop();
            System.clearProperty("LOG_DIR");
        }
    }
}
