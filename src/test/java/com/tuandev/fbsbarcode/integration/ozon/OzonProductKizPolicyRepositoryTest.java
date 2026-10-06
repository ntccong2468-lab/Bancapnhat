package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonProductKizPolicyRepositoryTest {
    @TempDir Path appData;
    private OzonProductKizPolicyRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client','secret')");
        }
        repository = new OzonProductKizPolicyRepository();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void absenceRequiresKizAndExplicitExceptionCanBeReverted() {
        assertTrue(repository.requiresKiz(1, "SKU-1"));

        repository.setRequired(1, "SKU-1", false);

        assertFalse(repository.requiresKiz(1, "SKU-1"));
        assertEquals(Set.of("SKU-1"), repository.findExemptSkus(1));

        repository.setRequired(1, "SKU-1", true);

        assertTrue(repository.requiresKiz(1, "SKU-1"));
        assertTrue(repository.findExemptSkus(1).isEmpty());
    }

    @Test
    void replacesShopExemptionsAtomically() {
        repository.replaceExemptSkus(1, Set.of("SKU-1", "SKU-2"));
        repository.replaceExemptSkus(1, Set.of("SKU-3"));

        assertEquals(Set.of("SKU-3"), repository.findExemptSkus(1));
    }
}
