package com.parizle.prices.bulletin;

public class BulletinParseException extends RuntimeException {

    public BulletinParseException(String message) {
        super(message);
    }

    public BulletinParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
