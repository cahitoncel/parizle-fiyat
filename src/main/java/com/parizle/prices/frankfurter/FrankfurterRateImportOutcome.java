package com.parizle.prices.frankfurter;

import com.parizle.prices.bulletin.domain.ImportStatus;
import java.time.LocalDate;
import java.util.List;

public record FrankfurterRateImportOutcome(
        ImportStatus status,
        LocalDate rateDate,
        int baseCount,
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
