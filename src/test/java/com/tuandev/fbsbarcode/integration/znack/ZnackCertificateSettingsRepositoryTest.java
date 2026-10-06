package com.tuandev.fbsbarcode.integration.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZnackCertificateSettingsRepositoryTest {
    @TempDir
    Path appData;

    private ZnackRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(7,'Main shop','secret')");
        }
        repository = new ZnackRepository(new ShopContext(7, "Main shop"));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void verifiedCertificateAndAuditCommitTogetherWhileStaleSnapshotRollsBack() throws Exception {
        Settings initial = settings("selector-a", null, "metadata-a");
        repository.saveSettings(initial);
        Settings verified = settings(
                "selector-b", Instant.parse("2026-07-18T00:00:00Z"), "metadata-b");

        ZnackRepository.CertificateVerificationResult result =
                repository.saveVerifiedCertificate(initial, verified);

        assertTrue(result.signerChanged());
        assertEquals(verified, repository.getSettings());
        assertEquals(1, signatureAuditCount());

        Settings concurrent = settings("selector-c", null, "metadata-c");
        repository.saveSettings(concurrent);
        assertThrows(
                ZnackRepository.SettingsConflictException.class,
                () -> repository.saveVerifiedCertificate(verified, settings(
                        "selector-d", Instant.parse("2026-07-18T01:00:00Z"), "metadata-d")));

        assertEquals(concurrent, repository.getSettings());
        assertEquals(1, signatureAuditCount());
    }

    @Test
    void signerChangeClearsMarketplaceMappingsAndRetiresOldCatalogWithoutDeletingKizHistory()
            throws Exception {
        Settings initial = settings("selector-a", Instant.parse("2026-07-18T00:00:00Z"),
                "{\"thumbprint\":\"AA 11\"}");
        repository.saveSettings(initial);
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO znack_products(shop_id,gtin,product_name,synced_at)
                    VALUES(7,'04600000000001','With history','2026-07-18T00:00:00Z'),
                          (7,'04600000000002','Unreferenced','2026-07-18T00:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at)
                    VALUES(101,7,'04600000000001',1,'CODES_DOWNLOADED',
                           '2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,status,legal_status,
                                          created_at,updated_at)
                    VALUES(7,101,'history-code','history-code','04600000000001','AVAILABLE','RECEIVED',
                           '2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO znack_gtin_mapping_rules(
                        shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at)
                    VALUES(7,'04600000000001','Shoes','*',1,
                           '2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at)
                    VALUES(7,'sku-1','04600000000001',
                           '2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO ozon_article_gtin_mappings(
                        shop_id,article_key,article,gtin,created_at,updated_at)
                    VALUES(7,'article-1','Article-1','04600000000001',
                           '2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                    """);
        }

        Settings verified = settings("selector-b", Instant.parse("2026-07-18T01:00:00Z"),
                "{\"thumbprint\":\"BB 22\"}");
        ZnackRepository.CertificateVerificationResult result =
                repository.saveVerifiedCertificate(initial, verified);

        assertTrue(result.signerChanged());
        assertEquals(3, result.clearedMappingCount());
        assertEquals(1, result.archivedProductCount());
        assertEquals(1, result.deletedProductCount());
        assertEquals(1, result.archivedCodeCount());
        assertTrue(repository.findProducts().isEmpty());
        assertTrue(repository.findDeletedProducts().isEmpty());
        assertTrue(repository.findProduct("04600000000001").isEmpty());
        assertEquals(1, count("znack_products"));
        assertEquals(1, count("kiz_orders"));
        assertEquals(1, count("kiz_codes"));
        assertEquals(1, countWhere("kiz_codes", "status='ARCHIVED'"));
        assertEquals(0, count("znack_gtin_mapping_rules"));
        assertEquals(0, count("ozon_product_gtin_mappings"));
        assertEquals(0, count("ozon_article_gtin_mappings"));
        assertEquals(1, countWhere("znack_operation_logs", "action='SIGNER_CHANGE'"));

        repository.upsertProducts(List.of(new ZnackModels.Product(
                "04600000000001", "Owned by new signer", "", "", "", "", "")));
        assertEquals(1, repository.findProducts().size());
        assertTrue(repository.findDeletedProducts().isEmpty());
        assertEquals(1, count("kiz_orders"));
        assertEquals(1, count("kiz_codes"));
    }

    @Test
    void sameThumbprintWithDifferentFormattingDoesNotResetCatalog() throws Exception {
        Settings initial = settings("selector-a", Instant.parse("2026-07-18T00:00:00Z"),
                "{\"thumbprint\":\"AA BB 11\"}");
        repository.saveSettings(initial);
        insertProduct("04600000000001");

        Settings verified = settings("different-selector", Instant.parse("2026-07-18T01:00:00Z"),
                "{\"thumbprint\":\"aabb11\"}");
        ZnackRepository.CertificateVerificationResult result =
                repository.saveVerifiedCertificate(initial, verified);

        assertFalse(result.signerChanged());
        assertEquals(1, repository.findProducts().size());
        assertEquals(0, countWhere("znack_operation_logs", "action='SIGNER_CHANGE'"));
    }

    @Test
    void firstCertificateVerificationDoesNotCountAsSignerChange() throws Exception {
        Settings initial = settings("", null, "");
        repository.saveSettings(initial);
        insertProduct("04600000000001");
        Settings verified = settings("selector-a", Instant.parse("2026-07-18T01:00:00Z"),
                "{\"thumbprint\":\"AA11\"}");

        ZnackRepository.CertificateVerificationResult result =
                repository.saveVerifiedCertificate(initial, verified);

        assertFalse(result.signerChanged());
        assertEquals(1, repository.findProducts().size());
    }

    @Test
    void activeKizWorkPreventsSignerChangeAndRollsBackSettings() throws Exception {
        Settings initial = settings("selector-a", Instant.parse("2026-07-18T00:00:00Z"),
                "{\"thumbprint\":\"AA11\"}");
        repository.saveSettings(initial);
        insertProduct("04600000000001");
        repository.createPipeline("04600000000001", 1);
        Settings verified = settings("selector-b", Instant.parse("2026-07-18T01:00:00Z"),
                "{\"thumbprint\":\"BB22\"}");

        assertThrows(ZnackRepository.SignerChangeBlockedException.class,
                () -> repository.saveVerifiedCertificate(initial, verified));

        assertEquals(initial, repository.getSettings());
        assertEquals(1, repository.findProducts().size());
        assertEquals(0, signatureAuditCount());
    }

    @Test
    void lateCatalogResponseFromPreviousSignerIsDiscarded() throws Exception {
        Settings initial = settings("selector-a", Instant.parse("2026-07-18T00:00:00Z"),
                "{\"thumbprint\":\"AA11\"}");
        repository.saveSettings(initial);
        Settings verified = settings("selector-b", Instant.parse("2026-07-18T01:00:00Z"),
                "{\"thumbprint\":\"BB22\"}");
        repository.saveVerifiedCertificate(initial, verified);

        assertThrows(ZnackRepository.StaleSignerException.class,
                () -> repository.upsertProducts(List.of(new ZnackModels.Product(
                        "04600000000001", "Old signer product", "", "", "", "", "")), initial));

        assertTrue(repository.findProducts().isEmpty());
    }

    private int signatureAuditCount() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*) FROM znack_operation_logs
                        WHERE shop_id=7 AND action='SIGNATURE_TEST' AND message='VERIFIED'
                        """)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void insertProduct(String gtin) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(7,'" + gtin + "','Product','2026-07-18T00:00:00Z')");
        }
    }

    private int count(String table) throws Exception {
        return countWhere(table, "1=1");
    }

    private int countWhere(String table, String condition) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + condition)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static Settings settings(String selector, Instant testedAt, String metadata) {
        return new Settings(
                "https://private.example", "https://private-suz.example", "OMS", "CONNECTION",
                "7700000000", "7700000000", "7700000000", "/private/signer", selector, "[]",
                "", "", "/private/pdf", false, "/private/cert-list", "[]", metadata, testedAt,
                "/private/certmgr", "/private/cryptcp", "/private/csptest", 60, "",
                Settings.DEFAULT_DOCUMENT_TYPE);
    }
}
