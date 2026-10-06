package com.tuandev.fbsbarcode.integration.znack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZnackErrorDetailsTest {
    @Test
    void includesFullHttpContextAndRedactsSecrets() {
        String longMessage = "validation detail; ".repeat(90);
        String longBody = "{\"error\":\"" + longMessage + "\",\"token\":\"secret-token\"}";
        var error = new ZnackApiClient.ZnackApiException(
                "Znack API request failed", 422, longBody, "POST", "https://example.test/v3/feed");

        String details = ZnackErrorDetails.format(error);

        assertTrue(details.contains("Summary: Znack API request failed (HTTP 422)"));
        assertTrue(details.contains("HTTP status: 422"));
        assertTrue(details.contains("Method: POST"));
        assertTrue(details.contains("URL: https://example.test/v3/feed"));
        assertTrue(details.contains(longMessage));
        assertTrue(details.contains("[REDACTED]"));
        assertFalse(details.contains("secret-token"));
    }
}
