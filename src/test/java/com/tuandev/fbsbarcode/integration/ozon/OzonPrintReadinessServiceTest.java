package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPrintReadinessServiceTest {
    private static final String GTIN = "04645588781154";

    @TempDir Path appData;
    private Shop shop;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        shop = new Shop(1, "Ozon", Marketplace.OZON, "client", "secret");
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client','secret')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'" + GTIN + "','Marked item','2026-08-24T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) "
                    + "VALUES(1,1,'" + GTIN + "',2,'COMPLETED','2026-08-24T00:00:00Z','2026-08-24T00:00:00Z')");
        }
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto(
                "101", "offer-1", "SKU-1", "Marked item", "https://cdn.example/item.jpg",
                false, "", List.of())), "cursor-1");
        new OzonPostingRepository().upsertDetail(1, posting());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void blocksBeforeFileSelectionWhenMandatorySkuIsNotMapped() {
        OzonPrintReadiness result = new OzonPrintReadinessService().inspect(shop, "POST-1");

        assertFalse(result.ready());
        assertEquals(List.of("SKU-1"), result.missingSkus());
        assertTrue(result.gtinAvailability().isEmpty());
    }

    @Test
    void productRequiresGtinMappingByDefaultEvenWhenOzonRequirementListIsEmpty() {
        new OzonPostingRepository().upsertDetail(1, unmarkedPosting());

        OzonPrintReadiness result = new OzonPrintReadinessService().inspect(shop, "POST-1");

        assertFalse(result.ready());
        assertEquals(List.of("SKU-1"), result.missingSkus());
    }

    @Test
    void explicitNoKizPolicyAllowsPrintingWithoutMappingUnlessOzonMarksItMandatory() {
        new OzonPostingRepository().upsertDetail(1, unmarkedPosting());
        new OzonProductKizPolicyRepository().setRequired(1, "SKU-1", false);

        OzonPrintReadiness result = new OzonPrintReadinessService().inspect(shop, "POST-1");

        assertTrue(result.ready());
        assertEquals(0, result.requiredKiz());
        assertTrue(result.missingSkus().isEmpty());
    }

    @Test
    void reportsRequiredAndAvailableKizForMappedGtin() throws Exception {
        new OzonProductGtinMappingRepository().put(1, "SKU-1", GTIN);
        insertKiz(1);

        OzonPrintReadiness result = new OzonPrintReadinessService().inspect(shop, "POST-1");

        assertFalse(result.ready());
        assertEquals(2, result.requiredKiz());
        assertEquals(new OzonPrintReadiness.GtinAvailability(GTIN, 2, 1),
                result.gtinAvailability().getFirst());
    }

    @Test
    void allowsSaveDialogOnlyWhenEveryRequiredKizIsAvailable() throws Exception {
        new OzonProductGtinMappingRepository().put(1, "SKU-1", GTIN);
        insertKiz(1);
        insertKiz(2);

        OzonPrintReadiness result = new OzonPrintReadinessService().inspect(shop, "POST-1");

        assertTrue(result.ready());
        assertEquals(2, result.requiredKiz());
        assertEquals(2, result.gtinAvailability().getFirst().available());
    }

    @Test
    void printAllCountsSharedInventoryAcrossEveryOrder() throws Exception {
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto(
                "102", "offer-2", "SKU-2", "Marked item 2", "", false, "", List.of())), "cursor-2");
        new OzonPostingRepository().upsertDetail(1, new OzonPostingDto(
                "POST-2", "ORDER-2", "ORDER-2", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of("102"), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "102", "SKU-2", "offer-2", "Marked item 2", 2, "RUB", "1"))));
        OzonProductGtinMappingRepository mappings = new OzonProductGtinMappingRepository();
        mappings.put(1, "SKU-1", GTIN);
        mappings.put(1, "SKU-2", GTIN);
        insertKiz(1);
        insertKiz(2);
        insertKiz(3);

        OzonPrintReadinessService service = new OzonPrintReadinessService();
        assertTrue(service.inspect(shop, "POST-1").ready());
        assertTrue(service.inspect(shop, "POST-2").ready());

        OzonBatchPrintReadiness batch = service.inspectAll(shop, List.of("POST-1", "POST-2"));

        assertFalse(batch.ready());
        assertEquals(new OzonPrintReadiness.GtinAvailability(GTIN, 4, 3),
                batch.gtinAvailability().getFirst());
    }

    private void insertKiz(int id) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) "
                    + "VALUES(" + id + ",1,1,'01" + GTIN + "21SERIAL-" + id + "','KIZ-" + id + "','"
                    + GTIN + "','AVAILABLE','IN_CIRCULATION','2026-08-24T00:00:00Z','2026-08-24T00:00:00Z')");
        }
    }

    private static OzonPostingDto posting() {
        return new OzonPostingDto("POST-1", "ORDER-1", "ORDER-1", "awaiting_deliver", "", "", "", "",
                "", "", new OzonRequirements(List.of("101"), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "101", "SKU-1", "offer-1", "Marked item", 2, "RUB", "1")));
    }

    private static OzonPostingDto unmarkedPosting() {
        return new OzonPostingDto("POST-1", "ORDER-1", "ORDER-1", "awaiting_deliver", "", "", "", "",
                "", "", new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "101", "SKU-1", "offer-1", "Marked item", 2, "RUB", "1")));
    }
}
