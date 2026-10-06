package com.tuandev.fbsbarcode.integration.ozon;

/** Ozon seller credential pair. Secret values are intentionally absent from toString(). */
public record OzonCredentials(String clientId, String apiKey) {
    public OzonCredentials {
        clientId = require(clientId, "Client ID", 256);
        apiKey = require(apiKey, "API key", 16 * 1024);
    }

    private static String require(String value, String label, int maximumLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()
                || normalized.length() > maximumLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A valid Ozon " + label + " is required.");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "OzonCredentials{clientIdConfigured=true, apiKey=<redacted>}";
    }
}
