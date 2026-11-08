package com.parizle.prices.currency;

public class TcmbRateException extends RuntimeException {

    public TcmbRateException(String message) {
        super(message);
    }

    public TcmbRateException(String message, Throwable cause) {
        super(message, cause);
    }
}
