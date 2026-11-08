package com.parizle.prices.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class JobRunLogTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void namesTheRunByDateAndJobAndClearsMdcOnClose() {
        try (JobRunLog ignored = JobRunLog.start("london-prices", LocalDate.of(2026, 11, 8))) {
            assertThat(MDC.get(JobRunLog.MDC_KEY)).isEqualTo("2026-11-08/london-prices");
        }

        assertThat(MDC.get(JobRunLog.MDC_KEY)).isNull();
    }
}
