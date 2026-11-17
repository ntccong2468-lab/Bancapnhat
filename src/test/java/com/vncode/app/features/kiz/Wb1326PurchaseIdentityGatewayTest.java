package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.CatalogReadRevision;
import com.vncode.app.integration.znack.CatalogRotationKeys;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/** Purchase identity admission is independent of a full Catalog credential scan. */
class Wb1326PurchaseIdentityGatewayTest {
    @TempDir Path directory;

    @BeforeEach void initialize() {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
    }

    @AfterEach void reset() {
        System.clearProperty("vncode.appdata.dir");
    }

    @ParameterizedTest
    @EnumSource(value = Marketplace.class, names = {"WILDBERRIES", "OZON"})
    void verifiedNativeIdentityCanEnterPurchaseWhileCatalogScanIsIncomplete(Marketplace market) throws Exception {
        var shop = PendingKizFixture.create(market);
        String id = market == Marketplace.WILDBERRIES ? "101" : "9001";
        var product = new KizPurchaseMappingProof.Product(id, "S-M", PendingKizFixture.GTIN);
        var identityBefore = KizPurchaseMappingProof.resolve(shop, List.of(product));

        CatalogRotationKeys.save(shop, "synthetic-A");
        CatalogRotationKeys.save(shop, "synthetic-B");
        CatalogRotationKeys.save(shop, "synthetic-A");

        assertFalse(CatalogReadRevision.complete(shop.getId()));
        assertEquals(identityBefore, KizPurchaseMappingProof.resolve(shop, List.of(product)));
        assertDoesNotThrow(() -> KizPurchaseMappingProof.require(shop, List.of(product), Set.of(PendingKizFixture.GTIN)));
        assertFalse(CatalogReadRevision.complete(shop.getId()), "Purchase identity admission must not mark a Catalog scan complete");
        var identity = new KizRegistrationIdentityRepository().readForPurchase(shop).select(id, "S-M");
        assertNotNull(identity);
        assertEquals(PendingKizFixture.GTIN, identity.gtin());
    }

    @ParameterizedTest
    @EnumSource(value = Marketplace.class, names = {"WILDBERRIES", "OZON"})
    void anotherVariantStillCannotAuthorizePurchase(Marketplace market) throws Exception {
        var shop = PendingKizFixture.create(market);
        CatalogRotationKeys.save(shop, "synthetic-A");
        CatalogRotationKeys.save(shop, "synthetic-B");
        CatalogRotationKeys.save(shop, "synthetic-A");
        String id = market == Marketplace.WILDBERRIES ? "101" : "9002";
        var product = new KizPurchaseMappingProof.Product(id, "S-L", PendingKizFixture.GTIN);
        assertThrows(KizAssignmentException.class,
                () -> KizPurchaseMappingProof.require(shop, List.of(product), Set.of(PendingKizFixture.GTIN)));
    }

    @ParameterizedTest
    @EnumSource(value = Marketplace.class, names = {"WILDBERRIES", "OZON"})
    void identityFromAnotherNativeProductStillCannotAuthorizePurchase(Marketplace market) throws Exception {
        var shop = PendingKizFixture.create(market);
        CatalogRotationKeys.save(shop, "synthetic-A");
        CatalogRotationKeys.save(shop, "synthetic-B");
        CatalogRotationKeys.save(shop, "synthetic-A");
        var product = new KizPurchaseMappingProof.Product("999999", "S-M", PendingKizFixture.GTIN);
        assertThrows(KizAssignmentException.class,
                () -> KizPurchaseMappingProof.require(shop, List.of(product), Set.of(PendingKizFixture.GTIN)));
    }
}
