package com.tuandev.fbsbarcode.integration.ozon;

import java.io.IOException;
import java.time.Duration;

/** Safe upstream failure: response bodies, credentials and posting identifiers are never retained. */
public final class OzonApiException extends IOException {
    private final String kind;
    private final int statusCode;
    private final boolean retryable;
    private final boolean ambiguousMutation;
    private final Duration retryAfter;
    private final String upstreamCode;

    public OzonApiException(
            String kind, int statusCode, boolean retryable, boolean ambiguousMutation, Throwable cause) {
        this(kind, statusCode, retryable, ambiguousMutation, cause, null, null);
    }

    OzonApiException(
            String kind, int statusCode, boolean retryable, boolean ambiguousMutation,
            Throwable cause, Duration retryAfter) {
        this(kind, statusCode, retryable, ambiguousMutation, cause, retryAfter, null);
    }

    OzonApiException(
            String kind, int statusCode, boolean retryable, boolean ambiguousMutation,
            Throwable cause, Duration retryAfter, String upstreamCode) {
        super(publicMessage(kind, statusCode, ambiguousMutation), cause);
        this.kind = safeKind(kind);
        this.statusCode = statusCode >= 400 && statusCode <= 599 ? statusCode : 0;
        this.retryable = retryable;
        this.ambiguousMutation = ambiguousMutation;
        this.retryAfter = retryAfter;
        this.upstreamCode = safeUpstreamCode(upstreamCode);
    }

    public String kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean ambiguousMutation() {
        return ambiguousMutation;
    }

    Duration retryAfter() {
        return retryAfter;
    }

    /** Allowlisted machine code only; response messages and bodies are never retained. */
    public String upstreamCode() {
        return upstreamCode;
    }

    public String safeErrorCode() {
        return upstreamCode == null ? kind : upstreamCode;
    }

    private static String publicMessage(String kind, int statusCode, boolean ambiguous) {
        if (ambiguous) {
            return "Ozon did not confirm the operation. WCode will reconcile remote state before retrying.";
        }
        return switch (safeKind(kind)) {
            case "credentials" -> "Ozon rejected the Client ID or API key.";
            case "rate_limited" -> "Ozon rate limit was reached. Please retry later.";
            case "unsupported" -> "This Ozon fulfillment requirement is not supported by WCode.";
            case "invalid_request" -> "Ozon rejected the request. Refresh the posting before trying again.";
            default -> statusCode == 0
                    ? "Ozon is temporarily unavailable."
                    : "Ozon request failed (HTTP " + statusCode + ").";
        };
    }

    private static String safeKind(String value) {
        return value != null && value.matches("[a-z][a-z0-9_]{0,31}") ? value : "upstream";
    }

    private static String safeUpstreamCode(String value) {
        if (value == null) return null;
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_:-]{0,63}") ? normalized : null;
    }
}
