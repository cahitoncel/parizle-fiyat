package com.parizle.prices.currency;

import com.parizle.prices.bulletin.domain.ImportStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves the TCMB forex buying rate for every currency used by bank accounts of portfolios whose
 * report currency is TRY and stores those rates in {@code currency_rate}.
 *
 * <p>TRY itself needs no rate because it is the report currency. A currency present in a bank
 * account but absent from the TCMB bulletin is reported as missing instead of failing the import.
 */
@Service
public class CurrencyRateImportService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyRateImportService.class);
    private static final String REPORT_CURRENCY = "TRY";

    private final TcmbRateClient client;
    private final CurrencyRateWriter writer;
    private final JdbcTemplate jdbcTemplate;

    public CurrencyRateImportService(TcmbRateClient client,
                                     CurrencyRateWriter writer,
                                     JdbcTemplate jdbcTemplate) {
        this.client = client;
        this.writer = writer;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CurrencyRateImportOutcome importRates(LocalDate rateDate) {
        Optional<TcmbRates> fetched;
        try {
            fetched = client.fetch(rateDate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TcmbRateException("Interrupted while fetching TCMB rates for " + rateDate, e);
        } catch (IOException e) {
            throw new TcmbRateException("Failed to fetch TCMB rates for " + rateDate, e);
        }

        if (fetched.isEmpty()) {
            return new CurrencyRateImportOutcome(ImportStatus.NOT_PUBLISHED, rateDate, 0, 0, 0, 0, 0, List.of());
        }

        TcmbRates rates = fetched.get();
        Map<String, BigDecimal> unitRates = new LinkedHashMap<>();
        for (TcmbRate rate : rates.rates()) {
            unitRates.putIfAbsent(rate.currencyCode(), rate.unitRate());
        }

        Map<UUID, Set<String>> currenciesByPortfolio = loadAccountCurrencies();
        Set<String> required = new TreeSet<>();
        for (Set<String> currencies : currenciesByPortfolio.values()) {
            for (String currency : currencies) {
                if (!REPORT_CURRENCY.equals(currency)) {
                    required.add(currency);
                }
            }
        }

        List<String> missing = required.stream()
                .filter(currency -> !unitRates.containsKey(currency))
                .toList();

        Map<UUID, Map<String, BigDecimal>> ratesByPortfolio = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<String>> portfolio : currenciesByPortfolio.entrySet()) {
            Map<String, BigDecimal> portfolioRates = new LinkedHashMap<>();
            for (String currency : portfolio.getValue()) {
                BigDecimal rate = unitRates.get(currency);
                if (!REPORT_CURRENCY.equals(currency) && rate != null) {
                    portfolioRates.put(currency, rate);
                }
            }
            if (!portfolioRates.isEmpty()) {
                ratesByPortfolio.put(portfolio.getKey(), portfolioRates);
            }
        }

        CurrencyRateWriteResult result = writer.write(rates.rateDate(), ratesByPortfolio);
        if (!missing.isEmpty()) {
            log.warn("TCMB bulletin for {} has no forex buying rate for {}", rates.rateDate(), missing);
        }
        log.info("TCMB rates {}: {} currencies in bulletin, {} TRY portfolios, {} required currencies, "
                        + "{} written, {} already existing",
                rates.rateDate(), rates.rates().size(), currenciesByPortfolio.size(), required.size(),
                result.writtenRows(), result.existingRows());

        return new CurrencyRateImportOutcome(ImportStatus.IMPORTED, rates.rateDate(), rates.rates().size(),
                currenciesByPortfolio.size(), required.size(), result.writtenRows(), result.existingRows(),
                missing);
    }

    private Map<UUID, Set<String>> loadAccountCurrencies() {
        Map<UUID, Set<String>> byPortfolio = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT p.id AS portfolio_id, ba.currency_code
                FROM portfolio p
                LEFT JOIN bank b ON b.portfolio_id = p.id AND b.deleted_at IS NULL
                LEFT JOIN bank_account ba ON ba.bank_id = b.id AND ba.deleted_at IS NULL
                WHERE p.currency = 'TRY' AND p.deleted_at IS NULL
                ORDER BY p.id, ba.currency_code
                """, rs -> {
            UUID portfolioId = rs.getObject("portfolio_id", UUID.class);
            Set<String> currencies = byPortfolio.computeIfAbsent(portfolioId, ignored -> new TreeSet<>());
            String code = rs.getString("currency_code");
            if (code != null) {
                currencies.add(code.trim().toUpperCase(Locale.ROOT));
            }
        });
        return byPortfolio;
    }
}
