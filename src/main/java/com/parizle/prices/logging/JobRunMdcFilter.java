package com.parizle.prices.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Lets only events emitted inside a job run reach the per-job file appender.
 */
public class JobRunMdcFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return event.getMDCPropertyMap().containsKey(JobRunLog.MDC_KEY)
                ? FilterReply.NEUTRAL
                : FilterReply.DENY;
    }
}
