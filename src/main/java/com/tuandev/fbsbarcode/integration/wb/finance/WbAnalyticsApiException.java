package com.tuandev.fbsbarcode.integration.wb.finance;

import java.time.Duration;

public class WbAnalyticsApiException extends RuntimeException {
    private final int statusCode;
    private final Duration retryAfter;

    public WbAnalyticsApiException(int statusCode, String message, Duration retryAfter) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public WbAnalyticsApiException(String message, Throwable cause) {
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
