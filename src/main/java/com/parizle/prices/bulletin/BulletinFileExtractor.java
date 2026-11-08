package com.parizle.prices.bulletin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Produces a CSV file from a downloaded bulletin.
 *
 * <p>BIST currently publishes Pay Piyasası bulletins as ZIP archives and VIOP bulletins as plain
 * CSV files, but the payload type is detected from the content rather than the file name so that
 * either market can switch format without code changes.
 */
@Component
public class BulletinFileExtractor {

    private static final long MAX_CSV_BYTES = 100L * 1024 * 1024;
    private static final int SNIFF_BYTES = 4096;

    public Path extractCsv(Path downloadedFile, Path targetDirectory) throws IOException {
        Files.createDirectories(targetDirectory);

        if (isZip(downloadedFile)) {
            return extractFirstCsvFromZip(downloadedFile, targetDirectory);
        }
        if (!looksLikeCsv(downloadedFile)) {
            throw new IOException("Downloaded file is neither a ZIP archive nor a CSV file: "
                    + downloadedFile.getFileName());
        }

        Path target = targetDirectory.resolve("bulletin.csv");
        copyWithLimit(Files.newInputStream(downloadedFile), target);
        return target;
    }

    private static boolean isZip(Path file) throws IOException {
        byte[] header = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(header);
            if (read < 4) {
                return false;
            }
        }
        return header[0] == 'P' && header[1] == 'K'
                && ((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6));
    }

    private static boolean looksLikeCsv(Path file) throws IOException {
        byte[] sample = new byte[SNIFF_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.read(sample);
        }
        String text = new String(sample, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        String trimmed = text.stripLeading();
        return !trimmed.isEmpty()
                && trimmed.charAt(0) != '<'
                && text.indexOf('\0') < 0
                && text.indexOf(';') >= 0;
    }

    private static Path extractFirstCsvFromZip(Path archive, Path targetDirectory) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                    continue;
                }
                Path target = targetDirectory.resolve(Path.of(entry.getName()).getFileName().toString());
                copyWithLimit(zip, target);
                return target;
            }
        }
        throw new IOException("No CSV entry found in " + archive.getFileName());
    }

    private static void copyWithLimit(InputStream in, Path target) throws IOException {
        long total = 0;
        try (InputStream source = in;
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CSV_BYTES) {
                    throw new IOException("CSV entry exceeds " + MAX_CSV_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
        }
    }
}
