package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.fbo.FboBarcodePrintItem;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPage;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPlan;
import com.tuandev.fbsbarcode.features.fbo.FboProductSearchCriteria;
import com.tuandev.fbsbarcode.features.fbo.FboProductSku;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.DownloadedCodes;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OzonFboWorkflowTest {
    private static final String GTIN = "04601234567890";
    @TempDir Path appData;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client','secret')");
        }
        new OzonCatalogRepository().upsertPage(1, List.of(
                product("101", "SKU-42", "BARCODE-42", "42", "Jackets", false),
                product("102", "SKU-44", "BARCODE-44", "44", "Trousers", false),
                product("103", "SKU-OLD", "BARCODE-OLD", "46", "Archived", true)), "cursor");
        new OzonProductKizPolicyRepository().setRequired(1, "SKU-44", false);

        ZnackRepository znack = new ZnackRepository(new ShopContext(1, "Ozon"));
        znack.upsertProducts(List.of(new Product(GTIN, "Jacket GTIN", null, null, null, null, null)));
        long order = znack.createDraft(GTIN, 2);
        znack.insertCodes(order, GTIN, new DownloadedCodes(List.of("KIZ-1", "KIZ-2"), "block"));
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE kiz_codes SET legal_status='IN_CIRCULATION' WHERE shop_id=1");
        }
        new OzonProductGtinMappingRepository().put(1, "SKU-42", GTIN);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void catalogRowsExposeOzonSkuBarcodeAndKizPolicy() {
        List<FboProductSku> products = new OzonFboProductRepository().search(
                new FboProductSearchCriteria(1, "Jacket", List.of(), 10, 0));

        assertEquals(2, products.size());
        FboProductSku size42 = products.stream().filter(product -> "42".equals(product.size())).findFirst().orElseThrow();
        FboProductSku size44 = products.stream().filter(product -> "44".equals(product.size())).findFirst().orElseThrow();
        assertEquals("SKU-42", size42.catalogSku());
        assertEquals("BARCODE-42", size42.sku());
        assertEquals("ART-42", size42.vendorCode());
        assertTrue(size42.requiresKiz());
        assertFalse(size44.requiresKiz());
    }

    @Test
    void categoryFilterSupportsSelectingMoreThanOneOzonCategory() {
        OzonFboProductRepository repository = new OzonFboProductRepository();

        assertEquals(List.of("Jackets", "Trousers"), repository.findCategories(1));
        assertEquals(List.of("SKU-44"), repository.search(
                        new FboProductSearchCriteria(1, "", List.of("Trousers"), 10, 0))
                .stream().map(FboProductSku::catalogSku).toList());
        assertEquals(List.of("SKU-42", "SKU-44"), repository.search(
                        new FboProductSearchCriteria(1, "", List.of("Jackets", "Trousers"), 10, 0))
                .stream().map(FboProductSku::catalogSku).sorted().toList());
    }

    @Test
    void printsTwoBarcodePagesThenOneKizPageForEachRequiredSize() {
        FboProductSku required = new OzonFboProductRepository().search(
                new FboProductSearchCriteria(1, "SKU-42", List.of(), 10, 0)).getFirst();

        FboPrintPlan plan = new OzonFboKizPrintPlanner().plan(
                1, List.of(new FboBarcodePrintItem(required, 2)));

        assertEquals(List.of(
                        FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.KIZ,
                        FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.KIZ),
                plan.pages().stream().map(FboPrintPage::kind).toList());
        assertEquals(java.util.Arrays.asList(null, null, "KIZ-1", null, null, "KIZ-2"),
                plan.pages().stream().map(FboPrintPage::kizCode).toList());
        assertEquals(2, plan.usedKizs().size());
    }

    @Test
    void explicitNoKizProductPrintsOnlyTheTwoBarcodePages() {
        FboProductSku exempt = new OzonFboProductRepository().search(
                new FboProductSearchCriteria(1, "SKU-44", List.of(), 10, 0)).getFirst();

        FboPrintPlan plan = new OzonFboKizPrintPlanner().plan(
                1, List.of(new FboBarcodePrintItem(exempt, 1)));

        assertEquals(2, plan.pages().size());
        assertTrue(plan.pages().stream().allMatch(page -> page.kind() == FboPrintPage.Kind.BARCODE));
        assertTrue(plan.usedKizs().isEmpty());
    }

    private static OzonProductDto product(
            String productId, String sku, String barcode, String size, String category, boolean archived) {
        return new OzonProductDto(
                productId, "offer-" + size, sku, "Jacket " + size, "https://example.test/" + size + ".png",
                "ART-" + size, "Black", size, category, "Men",
                archived, "2026-08-25T00:00:00Z", List.of(barcode));
    }
}
