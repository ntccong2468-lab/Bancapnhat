package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationSigningConsentTest {
    @TempDir Path directory;
    final String gtin=GtinWriteTestFixture.GTIN;
    Shop wb,ozon;
    ProductKey wbKey=new ProductKey(1,Marketplace.WILDBERRIES,"501","601");
    ProductKey ozonKey=new ProductKey(2,Marketplace.OZON,"000501","offer-001");
    final ZnackNationalCatalogService.SigningDocument document=new ZnackNationalCatalogService.SigningDocument(91,"<approved/>".getBytes(StandardCharsets.UTF_8));
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);var scope=scope(shop);
            try(var c=Database.getConnection()){
                if(shop==wb){
                    try(var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,nm_id,chrt_id,gtin,good_id,payload_json,status,created_at,updated_at) VALUES(?,?,?,?,91,?,'READY_TO_SIGN','fixture','fixture')")){
                        p.setInt(1,shop.getId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);p.setString(5,payload("Approved"));p.executeUpdate();
                    }
                }else{
                    try(var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,good_id,payload_json,scope_json,owner,created_at,updated_at) VALUES(?,?,?,'9001','READY_TO_SIGN',?,91,?,?,?,'fixture','fixture')")){
                        p.setInt(1,shop.getId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);p.setString(5,payload("Approved"));p.setString(6,new Gson().toJson(scope));p.setString(7,ListingIdentity.fingerprint(shop));p.executeUpdate();
                    }
                }
            }
            if(shop==wb)new ZnackCardRegistrationRepository().bindScope(shop,601,scope);
        }
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void persistedPayloadApprovalIsNotAnApprovalOfUnreviewedXml() {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);RegistrationSigningConsent.set(shop,key,gtin,true);
            assertTrue(RegistrationSigningConsent.allowed(shop,key,gtin));
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin,document));
            assertTrue(RegistrationSigningConsent.bindVerifiedDocument(shop,key,gtin,document));
            assertTrue(RegistrationSigningConsent.allowed(shop,key,gtin,document));
            var changed=new ZnackNationalCatalogService.SigningDocument(91,"<changed/>".getBytes(StandardCharsets.UTF_8));
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin,changed));
            assertFalse(RegistrationSigningConsent.bindVerifiedDocument(shop,key,gtin,changed));
        }
    }
    @Test void changingNativePayloadRevokesApprovalEvenWithSameGtin()throws Exception {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);RegistrationSigningConsent.set(shop,key,gtin,true);mutate(shop,"payload_json",payload("Changed"));
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin));
            assertFalse(RegistrationSigningConsent.bindVerifiedDocument(shop,key,gtin,document));
        }
    }
    @Test void existingCardsWithoutPayloadRequireExplicitFrozenXmlReview()throws Exception {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);mutate(shop,"payload_json","");
            assertThrows(IllegalArgumentException.class,()->RegistrationSigningConsent.set(shop,key,gtin,true));
            RegistrationSigningConsent.set(shop,key,gtin,true,document);
            assertTrue(RegistrationSigningConsent.allowed(shop,key,gtin,document));
            mutate(shop,"good_id","92");assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin));
        }
    }
    @Test void revocationCannotBeUndoneByDocumentBinding() {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);RegistrationSigningConsent.set(shop,key,gtin,true);RegistrationSigningConsent.set(shop,key,gtin,false);
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin));
            assertFalse(RegistrationSigningConsent.bindVerifiedDocument(shop,key,gtin,document));
        }
    }
    @Test void legacyUnboundConsentIsRetainedButCannotAuthorizeSigning()throws Exception {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_registration_signing_consents(shop_id,marketplace,product_id,variant_id,gtin,owner,enabled,updated_at) VALUES(?,?,?,?,?,?,1,'legacy')")){
                p.setInt(1,shop.getId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,gtin);p.setString(6,ListingIdentity.fingerprint(shop));p.executeUpdate();
            }
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin));
        }
    }
    @Test void withdrawalDoesNotRequireLegacyNativeScopeOrVerifiedCertificate()throws Exception {
        RegistrationSigningConsent.set(wb,wbKey,gtin,true);
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("DELETE FROM znack_card_registration_scopes WHERE shop_id=1");}
        assertDoesNotThrow(()->RegistrationSigningConsent.set(wb,wbKey,gtin,false));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT enabled FROM znack_registration_signing_consents WHERE shop_id=1")){
            assertTrue(r.next());assertEquals(0,r.getInt(1));
        }
    }
    @Test void certificateAndEnvironmentChangesRequireFreshApproval() {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);RegistrationSigningConsent.set(shop,key,gtin,true,document);
            GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin,document));
            GtinWriteTestFixture.saveSettings(shop,GtinWriteTestFixture.CERTIFICATE,"https://example.invalid/api/v3/true-api");
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin,document));
        }
    }
    @Test void wrongRemoteIdentityCannotBeApprovedOrBound() {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);var wrong=new ZnackNationalCatalogService.SigningDocument(92,document.xml());
            assertThrows(IllegalArgumentException.class,()->RegistrationSigningConsent.set(shop,key,gtin,true,wrong));
            RegistrationSigningConsent.set(shop,key,gtin,true);
            assertFalse(RegistrationSigningConsent.bindVerifiedDocument(shop,key,gtin,wrong));
        }
    }
    @Test void aKnownRemoteIdIsBoundBeforeTheFirstXmlRead()throws Exception {
        for(var shop:List.of(wb,ozon)){
            var key=key(shop);RegistrationSigningConsent.set(shop,key,gtin,true);
            mutate(shop,"good_id","92");
            assertFalse(RegistrationSigningConsent.allowed(shop,key,gtin));
            assertFalse(RegistrationSigningConsent.bindVerifiedDocument(shop,key,gtin,new ZnackNationalCatalogService.SigningDocument(92,document.xml())));
        }
    }
    @Test void consentDoesNotCrossVariantOrShopCredentials()throws Exception {
        RegistrationSigningConsent.set(ozon,ozonKey,gtin,true,document);
        assertFalse(RegistrationSigningConsent.allowed(ozon,new ProductKey(2,Marketplace.OZON,"501","offer-001"),gtin));
        mutate(ozon,"owner","other-account");assertFalse(RegistrationSigningConsent.allowed(ozon,ozonKey,gtin));
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE shops SET api_key='different' WHERE id=2");}
        assertThrows(SecurityException.class,()->RegistrationSigningConsent.allowed(ozon,ozonKey,gtin));
    }
    @Test void additiveMigrationKeepsLegacyRowsAndIsIdempotent()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("DROP TABLE znack_registration_signing_consents");
            s.execute("CREATE TABLE znack_registration_signing_consents(shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,gtin TEXT NOT NULL,owner TEXT NOT NULL,enabled INTEGER NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,marketplace,product_id,variant_id,gtin,owner))");
            s.execute("INSERT INTO znack_registration_signing_consents VALUES(1,'WILDBERRIES','501','601','00000000000017','legacy-owner',1,'legacy')");
            RegistrationSigningConsent.initialize(c);RegistrationSigningConsent.initialize(c);
            try(var r=s.executeQuery("SELECT enabled,updated_at,payload_sha256,scope_sha256 FROM znack_registration_signing_consents")){
                assertTrue(r.next());assertEquals(1,r.getInt(1));assertEquals("legacy",r.getString(2));assertEquals("",r.getString(3));assertEquals("",r.getString(4));
            }
        }
    }
    ProductKey key(Shop shop){return shop==wb?wbKey:ozonKey;}
    ZnackRegistrationScope scope(Shop shop){return ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());}
    String payload(String name){return "{\"gtin\":\""+gtin+"\",\"good_name\":\""+name+"\"}";}
    void mutate(Shop shop,String column,String value)throws Exception {
        String table=shop==wb?"znack_card_registrations":"ozon_card_registrations";
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE "+table+" SET "+column+"=? WHERE shop_id=?")){
            p.setString(1,value);p.setInt(2,shop.getId());p.executeUpdate();
        }
    }
}
