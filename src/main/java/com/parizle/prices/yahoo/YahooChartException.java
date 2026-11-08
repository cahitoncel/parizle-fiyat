package com.parizle.prices.yahoo;

public class YahooChartException extends RuntimeException {

    public YahooChartException(String message) {
        super(message);
    }

    public YahooChartException(String message, Throwable cause) {
        super(message, cause);
    }
}
