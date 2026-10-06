package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryItem;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryJobSummary;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryRepository;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPrintHistoryServiceTest {
    @TempDir Path temporaryDirectory;
    private Shop shop;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", temporaryDirectory.resolve("appdata").toString());
        Database.initDatabase();
        shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,?,?,?,?)")) {
            statement.setString(1, shop.getName());
            statement.setString(2, Marketplace.OZON.name());
            statement.setString(3, shop.getClientId());
            statement.setString(4, shop.getApiKey());
            statement.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void recordsOzonExternalIdsWithoutCopyingRawKizOrEnablingWbReprint() {
        OzonPostingDto posting = new OzonPostingDto(
                "POST-1", "ORDER-ID-1", "100001", "awaiting_deliver", "", "warehouse",
                "2026-08-19T08:30:00Z", "", "", "",
                new OzonRequirements(List.of("1001"), List.of(), List.of()),
                List.of("ship_available"), true,
                List.of(new OzonPostingItemDto(
                        0, "1001", "SKU-3583", "offer-black-176", "Sports suit", 2, "RUB", "1990")));

        long jobId = new OzonPrintHistoryService().recordSuccessfulJob(
                shop, posting, Instant.parse("2026-08-19T06:11:00Z"));

        PrintHistoryRepository repository = new PrintHistoryRepository();
        PrintHistoryJobSummary job = repository.findJobsByShop(1).getFirst();
        List<PrintHistoryItem> items = repository.findItems(jobId);
        assertEquals("OZON", job.marketplace());
        assertEquals("POST-1", job.supplyId());
        assertEquals("Ozon FBS 100001", job.supplyName());
        assertEquals(2, job.itemCount());
        assertFalse(job.canReprint());
        assertEquals(1, items.size());
        assertEquals("ORDER-ID-1", items.getFirst().externalOrderId());
        assertEquals("1001", items.getFirst().externalItemId());
        assertEquals("SKU-3583", items.getFirst().barcode());
        assertNull(items.getFirst().kiz());
    }
}
