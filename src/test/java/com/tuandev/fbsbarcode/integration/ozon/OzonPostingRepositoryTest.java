package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tuandev.fbsbarcode.config.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPostingRepositoryTest {
    @TempDir
    Path appData;

    private OzonPostingRepository postings;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client','secret')");
        }
        postings = new OzonPostingRepository();
        postings.upsertPage(1, List.of(
                posting("OLD-CANCELLED", "cancelled", "2026-08-01T08:30:00Z"),
                posting("READY-TO-PRINT", "awaiting_deliver", "2026-08-19T08:30:00Z"),
                posting("PACK-NOW", "awaiting_packaging", "2026-08-20T08:30:00Z"),
                posting("DELIVERING", "delivering", "2026-08-21T08:30:00Z")));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void activePackingQueueContainsOnlyPackagingAndReadyToPrintNewestFirst() {
        assertEquals(
                List.of("PACK-NOW", "READY-TO-PRINT"),
                postings.findActive(1, 100, 0).stream().map(OzonPostingDto::postingNumber).toList());
    }

    @Test
    void allPostingsAreNewestFirst() {
        assertEquals("DELIVERING", postings.findByStatus(1, null, 100, 0).getFirst().postingNumber());
    }

    @Test
    void boundedPageFiltersStatusAndSearchesPostingOrItemWithoutWildcardExpansion() {
        assertEquals(
                List.of("PACK-NOW"),
                postings.findPage(1, "active", "pack-now", 2, 0).stream()
                        .map(OzonPostingDto::postingNumber)
                        .toList());
        assertEquals(
                List.of("DELIVERING"),
                postings.findPage(1, "delivering", "sku-delivering", 2, 0).stream()
                        .map(OzonPostingDto::postingNumber)
                        .toList());
        assertEquals(List.of(), postings.findPage(1, "all", "%", 2, 0));
    }

    private static OzonPostingDto posting(String number, String status, String shipmentAt) {
        return new OzonPostingDto(
                number, number, number, status, "", "warehouse", shipmentAt, "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(
                        0, "1001", "SKU-" + number, "offer-" + number, "Product", 1, "RUB", "1")));
    }
}
