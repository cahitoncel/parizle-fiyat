package com.parizle.prices.frankfurter;

public class FrankfurterRateException extends RuntimeException {

    public FrankfurterRateException(String message) {
        super(message);
    }

    public FrankfurterRateException(String message, Throwable cause) {
        super(message, cause);
    }
}
