package com.parizle.prices.bulletin;

import com.parizle.prices.bulletin.domain.AssetType;
import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.bulletin.domain.MarketPrice;
import com.parizle.prices.bulletin.domain.ViopBulletinRow;
import com.parizle.prices.bulletin.service.AssetPriceHistoryWriter;
import com.parizle.prices.bulletin.service.PriceWriteResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

@Service
public class ViopBulletinImportService {

    private static final Logger log = LoggerFactory.getLogger(ViopBulletinImportService.class);

    private final BulletinUrlBuilder urlBuilder;
    private final BulletinDownloadClient downloadClient;
    private final BulletinFileExtractor fileExtractor;
    private final ViopBulletinCsvParser csvParser;
    private final AssetPriceHistoryWriter priceWriter;

    public ViopBulletinImportService(BulletinUrlBuilder urlBuilder,
                                     BulletinDownloadClient downloadClient,
                                     BulletinFileExtractor fileExtractor,
                                     ViopBulletinCsvParser csvParser,
                                     AssetPriceHistoryWriter priceWriter) {
        this.urlBuilder = urlBuilder;
        this.downloadClient = downloadClient;
        this.fileExtractor = fileExtractor;
        this.csvParser = csvParser;
        this.priceWriter = priceWriter;
    }

    public ImportOutcome importBulletin(LocalDate tradeDate) {
        URI sourceUri = urlBuilder.buildViop(tradeDate);
        Path downloadedFile = null;
        Path workDirectory = null;

        try {
            log.info("Importing BIST VIOP bulletin for {}", tradeDate);
            Optional<DownloadedBulletin> downloaded = downloadClient.downloadViop(tradeDate);
            if (downloaded.isEmpty()) {
                log.info("VIOP bulletin for {} is not published yet: {}", tradeDate, sourceUri);
                return new ImportOutcome(ImportStatus.NOT_PUBLISHED, tradeDate, 0, 0, 0, 0, 0);
            }

            DownloadedBulletin bulletin = downloaded.get();
            downloadedFile = bulletin.file();
            workDirectory = Files.createTempDirectory("bist-viop-");
            Path csvFile = fileExtractor.extractCsv(downloadedFile, workDirectory);
            List<ViopBulletinRow> rows = csvParser.parse(csvFile);
            PriceWriteResult result = priceWriter.write(tradeDate, AssetType.FUTURE, toPrices(rows));

            log.info("VIOP bulletin {}: {} rows, {} written, {} skipped, {} unmatched, {} already existing",
                    tradeDate, rows.size(), result.writtenRows(), result.skippedRows(),
                    result.unmatchedRows(), result.existingRows());
            return new ImportOutcome(ImportStatus.IMPORTED, tradeDate, rows.size(),
                    result.writtenRows(), result.skippedRows(), result.unmatchedRows(), result.existingRows());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BulletinImportException("Interrupted while importing VIOP bulletin " + sourceUri, e);
        } catch (Exception e) {
            throw new BulletinImportException("Failed to import VIOP bulletin " + sourceUri, e);
        } finally {
            deleteFile(downloadedFile);
            deleteDirectory(workDirectory);
        }
    }

    private static List<MarketPrice> toPrices(List<ViopBulletinRow> rows) {
        return rows.stream()
                .map(row -> new MarketPrice(
                        row.tradeDate(),
                        row.instrumentSeries(),
                        // VIOP rows without trades have no closing price; settlement is the official daily value.
                        positiveOrNull(row.closingPrice(), row.settlementPrice()),
                        positiveOrNull(row.openingPrice()),
                        positiveOrNull(row.highestPrice()),
                        positiveOrNull(row.lowestPrice()),
                        row.tradeVolume()))
                .toList();
    }

    private static BigDecimal positiveOrNull(BigDecimal preferred, BigDecimal fallback) {
        BigDecimal value = positiveOrNull(preferred);
        return value != null ? value : positiveOrNull(fallback);
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static void deleteFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete downloaded file {}", file, e);
        }
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            FileSystemUtils.deleteRecursively(directory);
        } catch (IOException e) {
            log.warn("Could not delete work directory {}", directory, e);
        }
    }
}
