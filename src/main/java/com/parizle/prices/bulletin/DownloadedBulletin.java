package com.parizle.prices.bulletin;

import java.net.URI;
import java.nio.file.Path;

public record DownloadedBulletin(
        Path file,
        URI sourceUri,
        String fileName,
        long size,
        String sha256
) {
}
