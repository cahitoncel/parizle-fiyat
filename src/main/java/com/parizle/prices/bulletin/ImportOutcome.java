package com.parizle.prices.bulletin;

import com.parizle.prices.bulletin.domain.ImportStatus;
import java.time.LocalDate;

public record ImportOutcome(
        ImportStatus status,
        LocalDate tradeDate,
        int totalRows,
        int writtenRows,
        int skippedRows,
        int unmatchedRows,
        int existingRows
) {

    public boolean imported() {
        return status == ImportStatus.IMPORTED;
    }
}
