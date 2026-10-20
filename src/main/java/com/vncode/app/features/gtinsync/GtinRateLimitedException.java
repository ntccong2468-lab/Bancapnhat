package com.vncode.app.features.gtinsync;

import java.io.IOException;
import java.time.Duration;

/** Retry is allowed solely because the seller API returned HTTP 429 without accepting the batch. */
public final class GtinRateLimitedException extends IOException {
    private final String code;private final Duration retryAfter;
    public GtinRateLimitedException(String code,Duration retryAfter,Throwable cause){super(code,cause);this.code=code;this.retryAfter=retryAfter;}
    public String code(){return code;}
    public Duration retryAfter(){return retryAfter;}
}
