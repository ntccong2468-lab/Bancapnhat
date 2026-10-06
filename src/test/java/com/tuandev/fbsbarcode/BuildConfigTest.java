package com.tuandev.fbsbarcode;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildConfigTest {

    @Test
    void getAppVersionReturnsNonNull() {
        String version = com.tuandev.fbsbarcode.BuildConfig.getAppVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
    }

    @Test
    void getUpdateUrlReturnsNonNull() {
        String url = com.tuandev.fbsbarcode.BuildConfig.getUpdateUrl();
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void getUpdateUrlReturnsValidUrl() {
        String url = com.tuandev.fbsbarcode.BuildConfig.getUpdateUrl();
        assertTrue(url.startsWith("http"));
    }
}
