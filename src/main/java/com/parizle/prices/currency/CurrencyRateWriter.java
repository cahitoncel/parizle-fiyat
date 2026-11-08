package com.parizle.prices.currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes daily rates into the existing {@code currency_rate} table.
 *
 * <p>Rates are portfolio-scoped because a portfolio may collect rates from a different source
 * date. Existing live rows for the same {@code (portfolio_id, currency, rate_date)} are left
 * untouched.
 */
@Component
public class CurrencyRateWriter {

    private final JdbcTemplate jdbcTemplate;

    public CurrencyRateWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CurrencyRateWriteResult write(LocalDate rateDate,
                                         Map<UUID, Map<String, BigDecimal>> ratesByPortfolio) {
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, BigDecimal>> portfolio : ratesByPortfolio.entrySet()) {
            for (Map.Entry<String, BigDecimal> rate : portfolio.getValue().entrySet()) {
                batch.add(new Object[]{portfolio.getKey(), rate.getKey(), rateDate, rate.getValue()});
            }
        }
        if (batch.isEmpty()) {
            return new CurrencyRateWriteResult(0, 0);
        }

        int[] results = jdbcTemplate.batchUpdate("""
                INSERT INTO currency_rate (portfolio_id, currency, rate_date, rate)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (portfolio_id, currency, rate_date) WHERE deleted_at IS NULL DO NOTHING
                """, batch);

        int written = 0;
        for (int result : results) {
            written += result;
        }
        return new CurrencyRateWriteResult(written, batch.size() - written);
    }
}
