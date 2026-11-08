package com.parizle.prices.frankfurter;

import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.currency.CurrencyRateWriteResult;
import com.parizle.prices.currency.CurrencyRateWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Fetches Frankfurter rates with every portfolio's own currency as the base and stores the quotes
 * used by its bank accounts in {@code currency_rate}.
 *
 * <p>TRY-base portfolios are skipped because the TCMB import is the source for those rates.
 *
 * <p>The stored direction follows {@code portfolio.is_currency_base} (Kural 42): when the portfolio
 * currency is the base ({@code true}) Frankfurter's quote-per-base rate is stored as returned; when
 * it is not ({@code false}) the rate is inverted so the row keeps the portfolio-per-quote direction
 * used by the TCMB import.
 */
@Service
public class FrankfurterRateImportService {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterRateImportService.class);
    private static final String TCMB_BASE_CURRENCY = "TRY";
    private static final int RATE_SCALE = 10;

    private final FrankfurterRateClient client;
    private final CurrencyRateWriter writer;
    private final JdbcTemplate jdbcTemplate;

    public FrankfurterRateImportService(FrankfurterRateClient client,
                                        CurrencyRateWriter writer,
                                        JdbcTemplate jdbcTemplate) {
        this.client = client;
        this.writer = writer;
        this.jdbcTemplate = jdbcTemplate;
    }

    public FrankfurterRateImportOutcome importRates(LocalDate requestedDate) {
        List<PortfolioCurrency> portfolios = loadPortfolios();
        Map<UUID, Set<String>> currenciesByPortfolio = loadAccountCurrencies();

        Map<String, Set<String>> quotesByBase = new LinkedHashMap<>();
        Map<UUID, Set<String>> requiredByPortfolio = new LinkedHashMap<>();
        for (PortfolioCurrency portfolio : portfolios) {
            Set<String> required = currenciesByPortfolio.getOrDefault(portfolio.id(), Set.of()).stream()
                    .filter(currency -> !portfolio.currency().equals(currency))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (required.isEmpty()) {
                continue;
            }
            requiredByPortfolio.put(portfolio.id(), required);
            quotesByBase.computeIfAbsent(portfolio.currency(), ignored -> new TreeSet<>()).addAll(required);
        }

        if (requiredByPortfolio.isEmpty()) {
            log.info("Frankfurter rates {}: no portfolio has a foreign bank-account currency", requestedDate);
            return new FrankfurterRateImportOutcome(ImportStatus.IMPORTED, requestedDate,
                    quotesByBase.size(), 0, 0, 0, 0, List.of());
        }

        Map<String, FrankfurterRates> fetchedByBase = fetchAll(requestedDate, quotesByBase);
        if (fetchedByBase.isEmpty()) {
            return new FrankfurterRateImportOutcome(ImportStatus.NOT_PUBLISHED, requestedDate,
                    quotesByBase.size(), requiredByPortfolio.size(), requiredCurrencies(requiredByPortfolio),
                    0, 0, List.of());
        }

        Set<String> missing = new TreeSet<>();
        Map<String, Map<UUID, Map<String, BigDecimal>>> ratesByBase = new LinkedHashMap<>();
        for (PortfolioCurrency portfolio : portfolios) {
            Set<String> required = requiredByPortfolio.get(portfolio.id());
            if (required == null) {
                continue;
            }
            FrankfurterRates fetched = fetchedByBase.get(portfolio.currency());
            if (fetched == null) {
                missing.addAll(required);
                continue;
            }

            Map<String, BigDecimal> portfolioRates = new LinkedHashMap<>();
            for (String currency : required) {
                BigDecimal rate = fetched.rates().get(currency);
                if (rate == null) {
                    missing.add(currency);
                    continue;
                }
                portfolioRates.put(currency, portfolio.isCurrencyBase() ? rate : invert(rate));
            }
            if (!portfolioRates.isEmpty()) {
                ratesByBase
                        .computeIfAbsent(portfolio.currency(), ignored -> new LinkedHashMap<>())
                        .put(portfolio.id(), portfolioRates);
            }
        }

        int written = 0;
        int existing = 0;
        for (Map.Entry<String, Map<UUID, Map<String, BigDecimal>>> entry : ratesByBase.entrySet()) {
            FrankfurterRates fetched = fetchedByBase.get(entry.getKey());
            CurrencyRateWriteResult result = writer.write(fetched.rateDate(), entry.getValue());
            written += result.writtenRows();
            existing += result.existingRows();
        }

        LocalDate rateDate = fetchedByBase.values().iterator().next().rateDate();
        if (!missing.isEmpty()) {
            log.warn("Frankfurter has no rate for {} on {}", missing, rateDate);
        }
        log.info("Frankfurter rates {}: {} bases, {} portfolios, {} required currencies, "
                        + "{} written, {} already existing",
                rateDate, quotesByBase.size(), requiredByPortfolio.size(),
                requiredCurrencies(requiredByPortfolio), written, existing);

        return new FrankfurterRateImportOutcome(ImportStatus.IMPORTED, rateDate,
                quotesByBase.size(), requiredByPortfolio.size(), requiredCurrencies(requiredByPortfolio),
                written, existing, List.copyOf(missing));
    }

    private Map<String, FrankfurterRates> fetchAll(LocalDate date, Map<String, Set<String>> quotesByBase) {
        Map<String, FrankfurterRates> fetched = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : quotesByBase.entrySet()) {
            try {
                Optional<FrankfurterRates> rates = client.fetch(date, entry.getKey(), entry.getValue());
                rates.ifPresent(value -> fetched.put(entry.getKey(), value));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FrankfurterRateException(
                        "Interrupted while fetching Frankfurter rates for base " + entry.getKey(), e);
            } catch (IOException e) {
                throw new FrankfurterRateException(
                        "Failed to fetch Frankfurter rates for base " + entry.getKey(), e);
            }
        }
        return fetched;
    }

    private List<PortfolioCurrency> loadPortfolios() {
        return jdbcTemplate.query("""
                SELECT id, currency, is_currency_base
                FROM portfolio
                WHERE deleted_at IS NULL AND currency <> ?
                ORDER BY id
                """, (rs, rowNum) -> new PortfolioCurrency(
                rs.getObject("id", UUID.class),
                normalize(rs.getString("currency")),
                rs.getBoolean("is_currency_base")), TCMB_BASE_CURRENCY);
    }

    private Map<UUID, Set<String>> loadAccountCurrencies() {
        Map<UUID, Set<String>> byPortfolio = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT p.id AS portfolio_id, ba.currency_code
                FROM portfolio p
                JOIN bank b ON b.portfolio_id = p.id AND b.deleted_at IS NULL
                JOIN bank_account ba ON ba.bank_id = b.id AND ba.deleted_at IS NULL
                WHERE p.deleted_at IS NULL
                ORDER BY p.id, ba.currency_code
                """, rs -> {
            UUID portfolioId = rs.getObject("portfolio_id", UUID.class);
            Set<String> currencies = byPortfolio.computeIfAbsent(portfolioId, ignored -> new LinkedHashSet<>());
            currencies.add(normalize(rs.getString("currency_code")));
        });
        return byPortfolio;
    }

    private static String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal invert(BigDecimal rate) {
        return BigDecimal.ONE.divide(rate, RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static int requiredCurrencies(Map<UUID, Set<String>> requiredByPortfolio) {
        Set<String> currencies = new TreeSet<>();
        requiredByPortfolio.values().forEach(currencies::addAll);
        return currencies.size();
    }

    private record PortfolioCurrency(UUID id, String currency, boolean isCurrencyBase) {
    }
}
