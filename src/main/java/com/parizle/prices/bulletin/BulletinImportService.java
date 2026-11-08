package com.parizle.prices.bulletin;

import com.parizle.prices.bulletin.domain.AssetType;
import com.parizle.prices.bulletin.domain.BulletinRow;
import com.parizle.prices.bulletin.domain.ImportStatus;
import com.parizle.prices.bulletin.domain.MarketPrice;
import com.parizle.prices.bulletin.service.AssetPriceHistoryWriter;
import com.parizle.prices.bulletin.service.PriceWriteResult;
import java.io.IOException;
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
public class BulletinImportService {

    private static final Logger log = LoggerFactory.getLogger(BulletinImportService.class);

    private final BulletinUrlBuilder urlBuilder;
    private final BulletinDownloadClient downloadClient;
    private final BulletinFileExtractor fileExtractor;
    private final BulletinCsvParser csvParser;
    private final AssetPriceHistoryWriter priceWriter;

    public BulletinImportService(BulletinUrlBuilder urlBuilder,
                                 BulletinDownloadClient downloadClient,
                                 BulletinFileExtractor fileExtractor,
                                 BulletinCsvParser csvParser,
                                 AssetPriceHistoryWriter priceWriter) {
        this.urlBuilder = urlBuilder;
        this.downloadClient = downloadClient;
        this.fileExtractor = fileExtractor;
        this.csvParser = csvParser;
        this.priceWriter = priceWriter;
    }

    public ImportOutcome importBulletin(LocalDate tradeDate, int session) {
        URI sourceUri = urlBuilder.build(tradeDate, session);
        Path downloadedFile = null;
        Path workDirectory = null;

        try {
            log.info("Importing BIST bulletin for {} session {}", tradeDate, session);
            Optional<DownloadedBulletin> downloaded = downloadClient.download(tradeDate, session);
            if (downloaded.isEmpty()) {
                log.info("Bulletin for {} session {} is not published yet: {}", tradeDate, session, sourceUri);
                return new ImportOutcome(ImportStatus.NOT_PUBLISHED, tradeDate, 0, 0, 0, 0, 0);
            }

            DownloadedBulletin bulletin = downloaded.get();
            downloadedFile = bulletin.file();
            workDirectory = Files.createTempDirectory("bist-bulletin-");
            Path csvFile = fileExtractor.extractCsv(downloadedFile, workDirectory);
            List<BulletinRow> rows = csvParser.parse(csvFile);
            PriceWriteResult result = priceWriter.write(tradeDate, AssetType.STOCK, toPrices(rows));

            log.info("Bulletin {} session {}: {} rows, {} written, {} skipped, {} unmatched, {} already existing",
                    tradeDate, session, rows.size(), result.writtenRows(), result.skippedRows(),
                    result.unmatchedRows(), result.existingRows());
            return new ImportOutcome(ImportStatus.IMPORTED, tradeDate, rows.size(),
                    result.writtenRows(), result.skippedRows(), result.unmatchedRows(), result.existingRows());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BulletinImportException("Interrupted while importing bulletin " + sourceUri, e);
        } catch (Exception e) {
            throw new BulletinImportException("Failed to import bulletin " + sourceUri, e);
        } finally {
            deleteFile(downloadedFile);
            deleteDirectory(workDirectory);
        }
    }

    private static List<MarketPrice> toPrices(List<BulletinRow> rows) {
        return rows.stream()
                .map(row -> new MarketPrice(
                        row.tradeDate(),
                        row.instrumentCode(),
                        row.closingPrice(),
                        row.openingPrice(),
                        row.highestPrice(),
                        row.lowestPrice(),
                        row.totalTradedVolume()))
                .toList();
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
