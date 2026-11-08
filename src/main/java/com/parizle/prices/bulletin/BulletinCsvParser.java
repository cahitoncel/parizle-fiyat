package com.parizle.prices.bulletin;

import com.parizle.prices.bulletin.domain.BulletinRow;
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
public class BulletinCsvParser {

    private static final char DELIMITER = ';';

    public List<BulletinRow> parse(Path csvFile) throws IOException {
        try (Reader reader = bomAwareReader(csvFile);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setDelimiter(DELIMITER)
                     .setIgnoreEmptyLines(true)
                     .build()
                     .parse(reader)) {
            Iterator<CSVRecord> records = parser.iterator();
            CSVRecord turkishHeader = nextRecord(records, "Turkish header");
            CSVRecord englishHeader = nextRecord(records, "English header");
            Columns columns = Columns.from(englishHeader);

            List<BulletinRow> rows = new ArrayList<>();
            while (records.hasNext()) {
                rows.add(map(records.next(), columns));
            }
            if (rows.isEmpty()) {
                throw new BulletinParseException("Bulletin contains a header but no data rows");
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
            throw new BulletinParseException("Bulletin is missing the " + name);
        }
        return records.next();
    }

    private static BulletinRow map(CSVRecord record, Columns c) {
        long row = record.getRecordNumber();
        if (record.size() != c.columnCount) {
            throw new BulletinParseException("Row " + row + ": expected " + c.columnCount
                    + " columns but found " + record.size());
        }
        return new BulletinRow(
                date(record, c.tradeDate),
                text(record, c.instrumentCode, "INSTRUMENT SERIES CODE", true),
                text(record, c.instrumentName, "INSTRUMENT NAME", true),
                text(record, c.marketSubSegment, "MARKET SUB SEGMENT", false),
                text(record, c.marketSegment, "MARKET SEGMENT", false),
                text(record, c.market, "MARKET", false),
                text(record, c.instrumentGroup, "INSTRUMENT GROUP", false),
                text(record, c.instrumentType, "INSTRUMENT TYPE", false),
                text(record, c.instrumentClass, "INSTRUMENT CLASS", false),
                text(record, c.tradingMethod, "TRADING METHOD", false),
                bool(record, c.marketMaker, "MARKET MAKER", row),
                bool(record, c.bist100Index, "BIST 100 INDEX", row),
                bool(record, c.bist30Index, "BIST 30 INDEX", row),
                bool(record, c.grossSettlement, "GROSS SETTLEMENT", row),
                text(record, c.corporateAction, "CORPORATE ACTION", false),
                bool(record, c.suspended, "SUSPENDED", row),
                bool(record, c.shortSell, "SHORT SELL", row),
                bool(record, c.bistParticipationAllIndex, "BIST PARTICIPATION ALL INDEX", row),
                decimal(record, c.previousLastPrice, "PREVIOUS LAST PRICE", row),
                decimal(record, c.openingPrice, "OPENING PRICE", row),
                decimal(record, c.openingSessionPrice, "OPENING SESSION PRICE", row),
                decimal(record, c.lowestPrice, "LOWEST PRICE", row),
                decimal(record, c.highestPrice, "HIGHEST PRICE", row),
                decimal(record, c.closingPrice, "CLOSING PRICE", row),
                decimal(record, c.closingSessionPrice, "CLOSING SESSION PRICE", row),
                decimal(record, c.changeToPreviousClosingPct, "CHANGE TO PREVIOUS CLOSING (%)", row),
                decimal(record, c.remainingBid, "REMAINING BID", row),
                decimal(record, c.remainingAsk, "REMAINING ASK", row),
                decimal(record, c.vwap, "VWAP", row),
                decimal(record, c.referencePrice, "REFERENCE PRICE", row),
                decimal(record, c.totalTradedValue, "TOTAL TRADED VALUE", row),
                decimal(record, c.totalTradedVolume, "TOTAL TRADED VOLUME", row),
                longValue(record, c.totalNumberOfContracts, "TOTAL NUMBER OF CONTRACTS", row),
                decimal(record, c.openingSessionTradedValue, "TRADED VALUE AT OPENING SESSION", row),
                decimal(record, c.openingSessionTradedVolume, "TRADED VOLUME AT OPENING SESSION", row),
                longValue(record, c.openingSessionContracts, "NUMBER OF CONTRACTS AT OPENING SESSION", row),
                decimal(record, c.closingSessionTradedValue, "TRADED VALUE AT CLOSING SESSION", row),
                decimal(record, c.closingSessionTradedVolume, "TRADED VOLUME AT CLOSING SESSION", row),
                longValue(record, c.closingSessionContracts, "NUMBER OF CONTRACTS AT CLOSING SESSION", row),
                decimal(record, c.closingPriceTradeValue, "TRADED VALUE OF TRADES AT CLOSING PRICE", row),
                decimal(record, c.closingPriceTradeVolume, "TRADED VOLUME OF TRADES AT CLOSING PRICE", row),
                longValue(record, c.closingPriceTradeContracts, "NUMBER OF CONTRACTS OF TRADES AT CLOSING PRICE", row),
                decimal(record, c.shortSaleLowestPrice, "LOWEST SHORT SALE PRICE", row),
                decimal(record, c.shortSaleHighestPrice, "HIGHEST SHORT SALE PRICE", row),
                decimal(record, c.shortSaleVwap, "SHORT SALE VWAP", row),
                decimal(record, c.shortSaleTradedValue, "TRADED VALUE OF SHORT SALE TRADES", row),
                decimal(record, c.shortSaleTradedVolume, "TRADED VOLUME OF SHORT SALE TRADES", row),
                longValue(record, c.shortSaleContracts, "NUMBER OF CONTRACTS OF SHORT SALE TRADES", row),
                decimal(record, c.tradeReportLowestPrice, "LOWEST TRADE REPORT PRICE", row),
                decimal(record, c.tradeReportHighestPrice, "HIGHEST TRADE REPORT PRICE", row),
                decimal(record, c.tradeReportVwap, "TRADE REPORT VWAP", row),
                decimal(record, c.tradeReportTradedValue, "TRADE REPORT TRADED VALUE", row),
                decimal(record, c.tradeReportTradedVolume, "TRADE REPORT TRADED VOLUME", row),
                longValue(record, c.tradeReportCount, "NUMBER OF TRADE REPORTS", row),
                decimal(record, c.midpointTradedValue, "MIDPOINT TRADED VALUE", row),
                decimal(record, c.midpointTradedVolume, "MIDPOINT TRADED VOLUME", row),
                longValue(record, c.midpointContracts, "MIDPOINT NUMBER OF CONTRACTS", row)
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

    private static LocalDate date(CSVRecord record, int index) {
        String value = text(record, index, "TRADE DATE", true);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BulletinParseException(
                    "Row " + record.getRecordNumber() + ": invalid TRADE DATE '" + value + "'", e);
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

    private static Boolean bool(CSVRecord record, int index, String column, long row) {
        String value = record.get(index).trim();
        if (value.isEmpty()) {
            return null;
        }
        return switch (value) {
            case "1", "true", "TRUE" -> Boolean.TRUE;
            case "0", "false", "FALSE" -> Boolean.FALSE;
            default -> throw new BulletinParseException("Row " + row + ": invalid " + column + " '" + value + "'");
        };
    }

    private record Columns(
            int tradeDate,
            int instrumentCode,
            int instrumentName,
            int marketSubSegment,
            int marketSegment,
            int market,
            int instrumentGroup,
            int instrumentType,
            int instrumentClass,
            int tradingMethod,
            int marketMaker,
            int bist100Index,
            int bist30Index,
            int grossSettlement,
            int corporateAction,
            int suspended,
            int shortSell,
            int bistParticipationAllIndex,
            int previousLastPrice,
            int openingPrice,
            int openingSessionPrice,
            int lowestPrice,
            int highestPrice,
            int closingPrice,
            int closingSessionPrice,
            int changeToPreviousClosingPct,
            int remainingBid,
            int remainingAsk,
            int vwap,
            int referencePrice,
            int totalTradedValue,
            int totalTradedVolume,
            int totalNumberOfContracts,
            int openingSessionTradedValue,
            int openingSessionTradedVolume,
            int openingSessionContracts,
            int closingSessionTradedValue,
            int closingSessionTradedVolume,
            int closingSessionContracts,
            int closingPriceTradeValue,
            int closingPriceTradeVolume,
            int closingPriceTradeContracts,
            int shortSaleLowestPrice,
            int shortSaleHighestPrice,
            int shortSaleVwap,
            int shortSaleTradedValue,
            int shortSaleTradedVolume,
            int shortSaleContracts,
            int tradeReportLowestPrice,
            int tradeReportHighestPrice,
            int tradeReportVwap,
            int tradeReportTradedValue,
            int tradeReportTradedVolume,
            int tradeReportCount,
            int midpointTradedValue,
            int midpointTradedVolume,
            int midpointContracts,
            int columnCount
    ) {

        static Columns from(CSVRecord englishHeader) {
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < englishHeader.size(); i++) {
                indexes.put(englishHeader.get(i).trim(), i);
            }
            return new Columns(
                    index(indexes, "TRADE DATE"),
                    index(indexes, "INSTRUMENT SERIES CODE"),
                    index(indexes, "INSTRUMENT NAME"),
                    index(indexes, "MARKET SUB SEGMENT"),
                    index(indexes, "MARKET SEGMENT"),
                    index(indexes, "MARKET"),
                    index(indexes, "INSTRUMENT GROUP"),
                    index(indexes, "INSTRUMENT TYPE"),
                    index(indexes, "INSTRUMENT CLASS"),
                    index(indexes, "TRADING METHOD"),
                    index(indexes, "MARKET MAKER"),
                    index(indexes, "BIST 100 INDEX"),
                    index(indexes, "BIST 30 INDEX"),
                    index(indexes, "GROSS SETTLEMENT"),
                    index(indexes, "CORPORATE ACTION"),
                    index(indexes, "SUSPENDED"),
                    index(indexes, "SHORT SELL"),
                    index(indexes, "BIST PARTICIPATION ALL INDEX"),
                    index(indexes, "PREVIOUS LAST PRICE"),
                    index(indexes, "OPENING PRICE"),
                    index(indexes, "OPENING SESSION PRICE"),
                    index(indexes, "LOWEST PRICE"),
                    index(indexes, "HIGHEST PRICE"),
                    index(indexes, "CLOSING PRICE"),
                    index(indexes, "CLOSING SESSION PRICE"),
                    index(indexes, "CHANGE TO PREVIOUS CLOSING (%)"),
                    index(indexes, "REMAINING BID"),
                    index(indexes, "REMAINING ASK"),
                    index(indexes, "VWAP"),
                    index(indexes, "REFERENCE PRICE"),
                    index(indexes, "TOTAL TRADED VALUE"),
                    index(indexes, "TOTAL TRADED VOLUME"),
                    index(indexes, "TOTAL NUMBER OF CONTRACTS"),
                    index(indexes, "TRADED VALUE AT OPENING SESSION"),
                    index(indexes, "TRADED VOLUME AT OPENING SESSION"),
                    index(indexes, "NUMBER OF CONTRACTS AT OPENING SESSION"),
                    index(indexes, "TRADED VALUE AT CLOSING SESSION"),
                    index(indexes, "TRADED VOLUME AT CLOSING SESSION"),
                    index(indexes, "NUMBER OF CONTRACTS AT CLOSING SESSION"),
                    index(indexes, "TRADED VALUE OF TRADES AT CLOSING PRICE"),
                    index(indexes, "TRADED VOLUME OF TRADES AT CLOSING PRICE"),
                    index(indexes, "NUMBER OF CONTRACTS OF TRADES AT CLOSING PRICE"),
                    index(indexes, "LOWEST SHORT SALE PRICE"),
                    index(indexes, "HIGHEST SHORT SALE PRICE"),
                    index(indexes, "SHORT SALE VWAP"),
                    index(indexes, "TRADED VALUE OF SHORT SALE TRADES"),
                    index(indexes, "TRADED VOLUME OF SHORT SALE TRADES"),
                    index(indexes, "NUMBER OF CONTRACTS OF SHORT SALE TRADES"),
                    index(indexes, "LOWEST TRADE REPORT PRICE"),
                    index(indexes, "HIGHEST TRADE REPORT PRICE"),
                    index(indexes, "TRADE REPORT VWAP"),
                    index(indexes, "TRADE REPORT TRADED VALUE"),
                    index(indexes, "TRADE REPORT TRADED VOLUME"),
                    index(indexes, "NUMBER OF TRADE REPORTS"),
                    index(indexes, "MIDPOINT TRADED VALUE"),
                    index(indexes, "MIDPOINT TRADED VOLUME"),
                    index(indexes, "MIDPOINT NUMBER OF CONTRACTS"),
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
