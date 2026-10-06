package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonProductGtinMappingRepositoryTest {
    private static final String FIRST_GTIN = "04645588781154";
    private static final String SECOND_GTIN = "04645588781161";

    @TempDir
    Path appData;

    private OzonProductGtinMappingRepository mappings;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client','secret')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,category,synced_at) VALUES"
                    + "(1,'" + FIRST_GTIN + "','Black suit','Clothing','2026-08-19T00:00:00Z'),"
                    + "(1,'" + SECOND_GTIN + "','Blue suit','Clothing','2026-08-19T00:00:00Z')");
        }
        new OzonCatalogRepository().upsertPage(1, List.of(
                product("101", "SKU-A", "offer-a"),
                product("102", "SKU-B", "offer-b"),
                product("103", "SKU-C", "offer-c")), "cursor");
        mappings = new OzonProductGtinMappingRepository();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void replaceForGtinMapsSelectedCatalogSkusAndRemovesDeselectedOnes() {
        mappings.put(1, "SKU-A", FIRST_GTIN);
        mappings.put(1, "SKU-B", SECOND_GTIN);

        mappings.replaceForGtin(1, FIRST_GTIN, List.of("SKU-B", "SKU-C"));

        assertEquals(Map.of("SKU-B", FIRST_GTIN, "SKU-C", FIRST_GTIN), mappings.findAll(1));
        assertEquals(2, new KizMappingRepository().findGtinSummaries(1).stream()
                .filter(summary -> FIRST_GTIN.equals(summary.gtin()))
                .findFirst().orElseThrow().mappingRuleCount());
    }

    @Test
    void replaceForGtinRejectsSkuOutsideTheOzonCatalog() {
        assertThrows(IllegalArgumentException.class,
                () -> mappings.replaceForGtin(1, FIRST_GTIN, List.of("UNKNOWN-SKU")));
    }

    @Test
    void articleMappingResolvesEveryCatalogSkuWithoutRequiringIndividualSkuMappings() {
        new OzonCatalogRepository().upsertPage(1, List.of(
                product("201", "SKU-SIZE-42", "shared-article", "Clothing", "Women"),
                product("202", "SKU-SIZE-44", "shared-article", "Clothing", "Women")), "cursor-2");

        mappings.replaceArticlesForGtin(1, FIRST_GTIN, List.of("shared-article"));

        assertEquals(Map.of("shared-article", FIRST_GTIN), mappings.findAllArticles(1));
        assertEquals(FIRST_GTIN, mappings.findResolvedBySku(1).get("SKU-SIZE-42"));
        assertEquals(FIRST_GTIN, mappings.findResolvedBySku(1).get("SKU-SIZE-44"));
    }

    @Test
    void legacySkuMappingIsSafelyInferredForSiblingSkusWithTheSameArticle() {
        new OzonCatalogRepository().upsertPage(1, List.of(
                product("301", "SKU-OLD-42", "legacy-article", "Clothing", "Women"),
                product("302", "SKU-NEW-44", "legacy-article", "Clothing", "Women")), "cursor-3");
        mappings.put(1, "SKU-OLD-42", FIRST_GTIN);

        assertEquals(FIRST_GTIN, mappings.findAllArticles(1).get("legacy-article"));
        assertEquals(FIRST_GTIN, mappings.findResolvedBySku(1).get("SKU-NEW-44"));
    }

    @Test
    void trashedZnackGtinIsNeverResolvedForOzonPacking() throws Exception {
        new OzonCatalogRepository().upsertPage(1, List.of(
                product("401", "SKU-TRASHED", "trashed-article", "Clothing", "Women")), "cursor-4");
        mappings.put(1, "SKU-TRASHED", FIRST_GTIN);
        mappings.replaceArticlesForGtin(1, FIRST_GTIN, List.of("trashed-article"));
        new ZnackRepository(new ShopContext(1, "Ozon")).softDeleteProducts(List.of(FIRST_GTIN));

        assertFalse(mappings.findResolvedBySku(1).containsKey("SKU-TRASHED"));
        assertFalse(mappings.findAll(1).containsValue(FIRST_GTIN));
        assertFalse(mappings.findAllArticles(1).containsValue(FIRST_GTIN));
        assertEquals(0, mappingCount("ozon_product_gtin_mappings", FIRST_GTIN));
        assertEquals(0, mappingCount("ozon_article_gtin_mappings", FIRST_GTIN));
    }

    @Test
    void startupRepairsOzonMappingsLeftByAnOlderVersionForATrashedGtin() throws Exception {
        mappings.put(1, "SKU-A", FIRST_GTIN);
        mappings.replaceArticlesForGtin(1, FIRST_GTIN, List.of("offer-a"));
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE znack_products SET deleted_at='2026-08-28T00:00:00Z' "
                    + "WHERE shop_id=1 AND gtin='" + FIRST_GTIN + "'");
        }
        assertEquals(1, mappingCount("ozon_product_gtin_mappings", FIRST_GTIN));
        assertEquals(1, mappingCount("ozon_article_gtin_mappings", FIRST_GTIN));

        Database.initDatabase();

        assertEquals(0, mappingCount("ozon_product_gtin_mappings", FIRST_GTIN));
        assertEquals(0, mappingCount("ozon_article_gtin_mappings", FIRST_GTIN));
    }

    private int mappingCount(String table, String gtin) throws Exception {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM " + table + " WHERE shop_id=1 AND gtin=?")) {
            statement.setString(1, gtin);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private static OzonProductDto product(String productId, String sku, String offerId) {
        return new OzonProductDto(productId, offerId, sku, "Product " + sku, "", false, "", List.of());
    }

    private static OzonProductDto product(
            String productId, String sku, String article, String category, String gender) {
        return new OzonProductDto(productId, article, sku, "Product " + sku, "", article,
                "black", "42", category, gender, false, "", List.of());
    }
}
