package com.parizle.prices.bulletin.service;

public record PriceWriteResult(
        int writtenRows,
        int skippedRows,
        int unmatchedRows,
        int existingRows
) {
}
