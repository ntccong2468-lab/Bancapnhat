package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FboKizPrintPlannerTest {
    private static final String GTIN = "04601234567890";
    @TempDir Path temp;

    @AfterEach void clear() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void reservesOneAtomicGtinPoolForFboPages() throws Exception {
        fixture(true, 2);
        FboPrintPlan plan = new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(true), 2)));
        assertEquals(6, plan.pages().size());
        assertEquals(List.of(
                        FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.KIZ,
                        FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.KIZ),
                plan.pages().stream().map(FboPrintPage::kind).toList());
        assertEquals(java.util.Arrays.asList(null, null, "KIZ-1", null, null, "KIZ-2"),
                plan.pages().stream().map(FboPrintPage::kizCode).toList());
        assertEquals(2, plan.usedKizs().size());
    }

    @Test
    void rejectsMissingMappingAndShortage() throws Exception {
        fixture(false, 2);
        assertThrows(IllegalStateException.class,
                () -> new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(true), 1))));

        clearDb();
        fixture(true, 1);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(true), 2))))
                .getMessage().contains(GTIN));
    }

    @Test
    void skipsInventoryForUnmarkedProduct() throws Exception {
        fixture(false, 0);
        FboPrintPlan plan = new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(false), 2)));
        assertEquals(4, plan.pages().size());
        assertTrue(plan.pages().stream().allMatch(page -> page.kind() == FboPrintPage.Kind.BARCODE));
        assertTrue(plan.pages().stream().allMatch(page -> page.kizCode() == null));
    }

    private void fixture(boolean mapping, int codeCount) throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop','token')");
            st.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,subject_name,need_kiz,synced_at) VALUES(1,1001,'ART','Shoes',1,'now')");
        }
        ZnackRepository repository = new ZnackRepository(new ShopContext(1, "Shop"));
        repository.upsertProducts(List.of(new Product(GTIN, "Shoes GTIN", null, null, null, null, null)));
        if (mapping) new KizMappingRepository().replaceRulesForGtin(1, GTIN,
                List.of(new ZnackGtinMappingSelection("Shoes", null, true)));
        long order = repository.createDraft(GTIN, Math.max(1, codeCount));
        for (int i = 1; i <= codeCount; i++) {
            repository.insertCodes(order, GTIN, new DownloadedCodes(List.of("KIZ-" + i), "block"));
        }
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE kiz_codes SET legal_status='IN_CIRCULATION' WHERE shop_id=1");
        }
    }

    private void clearDb() throws Exception {
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM shops");
        }
    }

    private FboProductSku product(boolean requiresKiz) {
        return new FboProductSku(1001, "ART", "Shoes", "Brand", "Product", "Black", "42", "42", "SKU", "", requiresKiz);
    }
}
