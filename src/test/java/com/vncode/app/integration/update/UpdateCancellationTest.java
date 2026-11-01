package com.vncode.app.integration.update;

import com.vncode.app.config.Database;
import com.vncode.app.shared.ConfigService;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCancellationTest {
    @TempDir Path root;

    @AfterEach void clearDirectory() { System.clearProperty("vncode.appdata.dir"); }

    @Test void cancelledCheckDoesNotRetryOrOpenTheNextSessionDatabase() throws Exception {
        Path first = root.resolve("first");
        Path next = root.resolve("next");
        System.setProperty("vncode.appdata.dir", first.toString());
        Database.initDatabase();
        ConfigService.setConfigValue("update_api_url", "https://fixture.invalid");
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            System.setProperty("vncode.appdata.dir", next.toString());
            throw new IOException("fixture cancelled network");
        }).build();

        UpdateApiClient client = new UpdateApiClient(http, "");
        try {
            assertThrows(IOException.class, client::fetchLatestVersion);
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, calls.get(), "a cancelled request must not be sent again");
        assertFalse(Files.exists(next.resolve("database.db")),
                "a closed session must not create a database in the next session");
    }

    @Test void explicitCancellationStopsRetryEvenWhenTransportClearsInterrupt() throws Exception {
        Path first = root.resolve("first");
        Path next = root.resolve("next");
        System.setProperty("vncode.appdata.dir", first.toString());
        Database.initDatabase();
        ConfigService.setConfigValue("update_api_url", "https://fixture.invalid");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            cancelled.set(true);
            System.setProperty("vncode.appdata.dir", next.toString());
            Thread.interrupted();
            throw new IOException("fixture cancelled network");
        }).build();

        UpdateApiClient client = new UpdateApiClient(http, "");
        var result = new UpdateService(client, () -> "1.0.0", () -> "").check(false, cancelled::get);
        assertEquals(UpdateService.Status.FAILED, result.status());
        assertEquals("UPDATE_CANCELLED", result.errorCode());
        assertEquals(1, calls.get());
        assertFalse(Files.exists(next.resolve("database.db")));
    }
}
