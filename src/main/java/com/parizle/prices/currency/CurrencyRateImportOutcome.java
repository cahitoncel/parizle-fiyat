package com.parizle.prices.currency;

import com.parizle.prices.bulletin.domain.ImportStatus;
import java.time.LocalDate;
import java.util.List;

public record CurrencyRateImportOutcome(
        ImportStatus status,
        LocalDate rateDate,
        int xmlCurrencyCount,
        int portfolioCount,
        int requiredCurrencyCount,
        int writtenRows,
        int existingRows,
        List<String> missingCurrencies
) {

    public boolean imported() {
        return status == ImportStatus.IMPORTED;
    }
}
