package com.parizle.prices.bulletin;

import com.parizle.prices.bulletin.domain.ViopBulletinRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class ViopBulletinCsvParser {

    private static final char DELIMITER = ';';

    public List<ViopBulletinRow> parse(Path csvFile) throws IOException {
        try (Reader reader = bomAwareReader(csvFile);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setDelimiter(DELIMITER)
                     .setIgnoreEmptyLines(true)
                     .build()
                     .parse(reader)) {
            Iterator<CSVRecord> records = parser.iterator();
            nextRecord(records, "Turkish header");
            CSVRecord englishHeader = nextRecord(records, "English header");
            Columns columns = Columns.from(englishHeader);

            List<ViopBulletinRow> rows = new ArrayList<>();
            while (records.hasNext()) {
                rows.add(map(records.next(), columns));
            }
            if (rows.isEmpty()) {
                throw new BulletinParseException("VIOP bulletin contains a header but no data rows");
            }
            return rows;
        }
    }

    private static Reader bomAwareReader(Path file) throws IOException {
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        reader.mark(1);
        if (reader.read() != 0xFEFF) {
            reader.reset();
        }
        return reader;
    }

    private static CSVRecord nextRecord(Iterator<CSVRecord> records, String name) {
        if (!records.hasNext()) {
            throw new BulletinParseException("VIOP bulletin is missing the " + name);
        }
        return records.next();
    }

    private static ViopBulletinRow map(CSVRecord record, Columns c) {
        long row = record.getRecordNumber();
        if (record.size() != c.columnCount) {
            throw new BulletinParseException("Row " + row + ": expected " + c.columnCount
                    + " columns but found " + record.size());
        }
        return new ViopBulletinRow(
                date(record, c.tradeDate, "TRADE DATE", true),
                text(record, c.instrumentSeries, "INSTRUMENT SERIES", true),
                text(record, c.instrumentName, "INSTRUMENT NAME", true),
                text(record, c.market, "MARKET", false),
                text(record, c.marketSegment, "MARKET SEGMENT", false),
                text(record, c.instrumentType, "INSTRUMENT TYPE", false),
                text(record, c.instrumentClass, "INSTRUMENT CLASS", false),
                text(record, c.underlying, "UNDERLYING", false),
                date(record, c.expirationDate, "EXPIRATION DATE", false),
                decimal(record, c.settlementPrice, "SETTLEMENT PRICE", row),
                decimal(record, c.previousSettlementPrice, "PREVIOUS SETTLEMENT PRICE", row),
                decimal(record, c.changeOfSettlementPricePct, "CHANGE OF SETTLEMENT PRICE (%)", row),
                decimal(record, c.openingPrice, "OPENING PRICE", row),
                decimal(record, c.lowestPrice, "LOWEST PRICE", row),
                decimal(record, c.highestPrice, "HIGHEST PRICE", row),
                decimal(record, c.closingPrice, "CLOSING PRICE", row),
                decimal(record, c.vwap, "VWAP", row),
                decimal(record, c.tradedValue, "TRADED VALUE", row),
                decimal(record, c.premiumValue, "PREMIUM VALUE", row),
                decimal(record, c.tradeVolume, "TRADE VOLUME", row),
                longValue(record, c.tradeCount, "TRADE COUNT", row),
                longValue(record, c.openPosition, "OPEN POSITION", row),
                longValue(record, c.openPositionChange, "OPEN POSITION CHANGE", row),
                decimal(record, c.openingSessionPrice, "OPENING SESSION PRICE", row),
                decimal(record, c.openingSessionTradedValue, "TRADED VALUE AT OPENING SESSION", row),
                decimal(record, c.openingSessionPremiumValue, "PREMIUM VALUE AT OPENING SESSION", row),
                decimal(record, c.openingSessionTradedVolume, "TRADED VOLUME AT OPENING SESSION", row),
                longValue(record, c.openingSessionTradeCount, "TRADE COUNT AT OPENING SESSION", row)
        );
    }

    private static String text(CSVRecord record, int index, String column, boolean required) {
        String value = record.get(index).trim();
        if (value.isEmpty()) {
            if (required) {
                throw new BulletinParseException("Row " + record.getRecordNumber() + ": " + column + " is empty");
            }
            return null;
        }
        return value;
    }

    private static LocalDate date(CSVRecord record, int index, String column, boolean required) {
        String value = text(record, index, column, required);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BulletinParseException(
                    "Row " + record.getRecordNumber() + ": invalid " + column + " '" + value + "'", e);
        }
    }

    private static BigDecimal decimal(CSVRecord record, int index, String column, long row) {
        String value = record.get(index).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new BulletinParseException("Row " + row + ": invalid " + column + " '" + value + "'", e);
        }
    }

    private static Long longValue(CSVRecord record, int index, String column, long row) {
        String value = record.get(index).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new BulletinParseException("Row " + row + ": invalid " + column + " '" + value + "'", e);
        }
    }

    private record Columns(
            int tradeDate,
            int instrumentSeries,
            int instrumentName,
            int market,
            int marketSegment,
            int instrumentType,
            int instrumentClass,
            int underlying,
            int expirationDate,
            int settlementPrice,
            int previousSettlementPrice,
            int changeOfSettlementPricePct,
            int openingPrice,
            int lowestPrice,
            int highestPrice,
            int closingPrice,
            int vwap,
            int tradedValue,
            int premiumValue,
            int tradeVolume,
            int tradeCount,
            int openPosition,
            int openPositionChange,
            int openingSessionPrice,
            int openingSessionTradedValue,
            int openingSessionPremiumValue,
            int openingSessionTradedVolume,
            int openingSessionTradeCount,
            int columnCount
    ) {

        static Columns from(CSVRecord englishHeader) {
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < englishHeader.size(); i++) {
                indexes.put(englishHeader.get(i).trim(), i);
            }
            return new Columns(
                    index(indexes, "TRADE DATE"),
                    index(indexes, "INSTRUMENT SERIES"),
                    index(indexes, "INSTRUMENT NAME"),
                    index(indexes, "MARKET"),
                    index(indexes, "MARKET SEGMENT"),
                    index(indexes, "INSTRUMENT TYPE"),
                    index(indexes, "INSTRUMENT CLASS"),
                    index(indexes, "UNDERLYING"),
                    index(indexes, "EXPIRATION DATE"),
                    index(indexes, "SETTLEMENT PRICE"),
                    index(indexes, "PREVIOUS SETTLEMENT PRICE"),
                    index(indexes, "CHANGE OF SETTLEMENT PRICE (%)"),
                    index(indexes, "OPENING PRICE"),
                    index(indexes, "LOWEST PRICE"),
                    index(indexes, "HIGHEST PRICE"),
                    index(indexes, "CLOSING PRICE"),
                    index(indexes, "VWAP"),
                    index(indexes, "TRADED VALUE"),
                    index(indexes, "PREMIUM VALUE"),
                    index(indexes, "TRADE VOLUME"),
                    index(indexes, "TRADE COUNT"),
                    index(indexes, "OPEN POSITION"),
                    index(indexes, "OPEN POSITION CHANGE"),
                    index(indexes, "OPENING SESSION PRICE"),
                    index(indexes, "TRADED VALUE AT OPENING SESSION"),
                    index(indexes, "PREMIUM VALUE AT OPENING SESSION"),
                    index(indexes, "TRADED VOLUME AT OPENING SESSION"),
                    index(indexes, "TRADE COUNT AT OPENING SESSION"),
                    englishHeader.size()
            );
        }

        private static int index(Map<String, Integer> indexes, String column) {
            Integer index = indexes.get(column);
            if (index == null) {
                throw new BulletinParseException("Missing expected column: " + column);
            }
            return index;
        }
    }
}
