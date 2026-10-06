package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FboProductRepositoryTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldExposeRuSizeFromWbSizeWhileKeepingSizeFallback() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1001, 'ART-1', 'Shoes', 'Brand', 'Product', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2001, 1001, 'EU-42', '42')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2001, 'SKU-1')
                    """);
        }

        List<FboProductSku> products = new FboProductRepository().search(new FboProductSearchCriteria(1, "", List.of(), 10, 0));

        assertEquals(1, products.size());
        assertEquals("EU-42", products.getFirst().size());
        assertEquals("42", products.getFirst().ruSize());
    }

    @Test
    void shouldResolveOnlyExactSkusOwnedByTheSelectedShop() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Main', 'token-1')");
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (2, 'Other', 'token-2')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1001, 'ART-1', 'Shoes', 'Brand', 'Main product', 'now'),
                           (2, 2001, 'ART-2', 'Shoes', 'Brand', 'Other product', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 3001, 1001, 'M', '44'),
                           (2, 3002, 2001, 'L', '46')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 3001, 'SHARED-SKU'),
                           (2, 3002, 'SHARED-SKU')
                    """);
        }

        List<FboProductSku> products =
                new FboProductRepository().findBySkus(1, List.of("SHARED-SKU", "MISSING"));

        assertEquals(1, products.size());
        assertEquals(1001, products.getFirst().nmId());
        assertEquals("Main product", products.getFirst().title());
        assertEquals("SHARED-SKU", products.getFirst().sku());
    }
}
