package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredCatalogOwnershipTest {
    @TempDir Path root;
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void publishedWbCardMustHaveOriginalOwnerCredentialStampBeforeExtraRegistryRead()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings());
        try(var c=Database.getConnection();var insert=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,status,created_at,updated_at) VALUES(1,11,101,?,'PUBLISHED','fixture','fixture')");var stamp=c.prepareStatement("INSERT INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,owner_hash,created_at) VALUES(1,11,?,?,?,?, 'fixture')")){
            insert.setString(1,GtinWriteTestFixture.GTIN);insert.executeUpdate();stamp.setString(1,scope.inn());stamp.setString(2,scope.environment());stamp.setString(3,scope.fingerprint());stamp.setString(4,ListingIdentity.fingerprint(shop));stamp.executeUpdate();
        }
        assertEquals(List.of(GtinWriteTestFixture.GTIN),NationalCatalogGtinSource.ownedPublishedGtins(shop,scope));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_card_registration_scopes SET owner_hash='' WHERE shop_id=1");}
        assertTrue(NationalCatalogGtinSource.ownedPublishedGtins(shop,scope).isEmpty());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_card_registration_scopes SET owner_hash=? WHERE shop_id=1")){p.setString(1,ListingIdentity.fingerprint(shop));p.executeUpdate();}
        GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        var current=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings());
        assertTrue(NationalCatalogGtinSource.ownedPublishedGtins(shop,current).isEmpty());
    }
}
