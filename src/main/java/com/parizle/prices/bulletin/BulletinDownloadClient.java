package com.parizle.prices.bulletin;

import com.parizle.prices.config.BistBulletinProperties;
import com.parizle.prices.config.BistViopProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BulletinDownloadClient {

    private static final Logger log = LoggerFactory.getLogger(BulletinDownloadClient.class);
    private static final String USER_AGENT = "parizle-prices/0.1 (+https://www.parizle.com)";
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final HttpClient httpClient;
    private final BulletinUrlBuilder urlBuilder;
    private final BistBulletinProperties properties;
    private final BistViopProperties viopProperties;

    public BulletinDownloadClient(HttpClient httpClient,
                                  BulletinUrlBuilder urlBuilder,
                                  BistBulletinProperties properties,
                                  BistViopProperties viopProperties) {
        this.httpClient = httpClient;
        this.urlBuilder = urlBuilder;
        this.properties = properties;
        this.viopProperties = viopProperties;
    }

    public Optional<DownloadedBulletin> download(LocalDate tradeDate, int session)
            throws IOException, InterruptedException {
        return download(urlBuilder.build(tradeDate, session), ".zip", properties.readTimeout(), true);
    }

    public Optional<DownloadedBulletin> downloadViop(LocalDate tradeDate)
            throws IOException, InterruptedException {
        return download(urlBuilder.buildViop(tradeDate), ".csv", viopProperties.readTimeout(), false);
    }

    private Optional<DownloadedBulletin> download(URI uri, String tempSuffix, Duration readTimeout, boolean requireZip)
            throws IOException, InterruptedException {
        Integer headStatus = headStatus(uri, readTimeout);

        if (headStatus != null) {
            if (headStatus == 404) {
                return Optional.empty();
            }
            if (headStatus / 100 != 2 && headStatus != 405) {
                throw new BulletinDownloadException("Unexpected HEAD status " + headStatus + " for " + uri);
            }
        }

        Path file = Files.createTempFile("bist-bulletin-", tempSuffix);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(readTimeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(file));

            if (response.statusCode() == 404) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                throw new BulletinDownloadException("Unexpected GET status " + response.statusCode() + " for " + uri);
            }

            long size = Files.size(file);
            if (size == 0) {
                throw new BulletinDownloadException("Downloaded file is empty: " + uri);
            }
            if (size > MAX_FILE_BYTES) {
                throw new BulletinDownloadException("Downloaded file exceeds " + MAX_FILE_BYTES + " bytes: " + uri);
            }
            if (requireZip) {
                assertZipSignature(file, uri);
            }

            DownloadedBulletin bulletin = new DownloadedBulletin(
                    file,
                    uri,
                    Path.of(uri.getPath()).getFileName().toString(),
                    size,
                    sha256(file)
            );
            log.info("Downloaded bulletin {} ({} bytes, sha256={})", uri, size, bulletin.sha256());
            return Optional.of(bulletin);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    private Integer headStatus(URI uri, Duration readTimeout) throws IOException, InterruptedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(readTimeout)
                    .header("User-Agent", USER_AGENT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            response.headers().firstValueAsLong("Content-Length").ifPresent(length -> {
                if (length > MAX_FILE_BYTES) {
                    throw new BulletinDownloadException("Remote file exceeds " + MAX_FILE_BYTES + " bytes: " + uri);
                }
            });
            return response.statusCode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void assertZipSignature(Path archive, URI uri) throws IOException {
        byte[] signature = new byte[2];
        try (InputStream in = Files.newInputStream(archive)) {
            if (in.read(signature) != signature.length || signature[0] != 'P' || signature[1] != 'K') {
                throw new BulletinDownloadException("Response is not a ZIP archive: " + uri);
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
