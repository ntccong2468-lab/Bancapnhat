package com.vncode.app.integration.wb;

import java.io.IOException;

public class WbApiException extends IOException {
    private final int statusCode;
    private final String responseBody;
    private final java.time.Duration retryAfter;

    public WbApiException(String message, int statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }

    public WbApiException(String message, int statusCode, String responseBody, java.time.Duration retryAfter) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.retryAfter = retryAfter;
    }

    public java.time.Duration getRetryAfter() { return retryAfter; }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public boolean isStatus(int expectedStatusCode) {
        return statusCode == expectedStatusCode;
    }

    public boolean isContentPermissionError() {
        return statusCode == 401 || statusCode == 403;
    }

    public boolean isRateLimited() {
        return statusCode == 429;
    }
}
