package com.parizle.prices.bulletin;

public class BulletinDownloadException extends RuntimeException {

    public BulletinDownloadException(String message) {
        super(message);
    }

    public BulletinDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
