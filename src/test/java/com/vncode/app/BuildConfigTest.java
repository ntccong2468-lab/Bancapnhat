package com.vncode.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildConfigTest {

    @Test
    void packagedIdentityUsesApprovedDisplayName() throws Exception {
        var properties = new java.util.Properties();
        try (var input = BuildConfig.class.getResourceAsStream("/app.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals("VN code", properties.getProperty("app.name"));
    }

    @Test
    void getAppVersionReturnsNonNull() {
        String version = com.vncode.app.BuildConfig.getAppVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
    }

    @Test
    void getUpdateUrlReturnsNonNull() {
        String url = com.vncode.app.BuildConfig.getUpdateUrl();
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void getUpdateUrlReturnsValidUrl() {
        String url = com.vncode.app.BuildConfig.getUpdateUrl();
        assertTrue(url.startsWith("http"));
    }
}
