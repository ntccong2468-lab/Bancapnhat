package com.tuandev.fbsbarcode.integration.ozon.finance;

import java.time.Duration;

public final class OzonFinanceApiException extends RuntimeException {
    private final int statusCode;
    private final Duration retryAfter;

    OzonFinanceApiException(int statusCode, String message, Duration retryAfter) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    OzonFinanceApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryAfter = null;
    }

    public int statusCode() {
        return statusCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
