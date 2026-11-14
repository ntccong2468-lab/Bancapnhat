package com.vncode.app.integration.ozon;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.CatalogReadRevision;
import com.vncode.app.integration.znack.registration.OzonReviewedGtinLinks;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static com.vncode.app.features.gtinsync.GtinWriteTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary SQLite and synthetic local transport, not real seller API acceptance. */
class OzonExactGtinAdapterTest {
    private static final String OFFER="Q47NAU-XXL";
    private static final String REVISION="fixture-exact-catalog-read";
    @TempDir Path root;
    private Shop shop;
    private OzonGtinAdapterTest.Fixture transport;
    private OzonGtinAdapter adapter;
    private LicenseService priorLicense,license;
    private String priorData;
    private final ProductKey key=new ProductKey(2,Marketplace.OZON,"501",OFFER);

    @BeforeEach void initialize()throws Exception {
        priorData=System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        priorLicense=LicenseRuntime.service();license=LicenseDomainFixture.active(root.resolve("license"));LicenseRuntime.install(license);
        shop=shop(Marketplace.OZON);
        transport=new OzonGtinAdapterTest.Fixture();transport.card.addProperty("offer_id",OFFER);
        transport.card.addProperty("price",1000);transport.size="XXL";transport.russianSize="52";
        transport.attributeBarcodeMirrorsInfo=true;
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES('catalog.read.revision.2',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
            p.setString(1,REVISION);p.executeUpdate();
        }
        saveCatalog(List.of(card(GTIN,"91")));
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,article,color,size,manufacturer_size,russian_size,category,archived,updated_at,synced_at) VALUES(2,'501',?,'9001','Fixture',?,'Blue','XXL','XXL','52','Fixture',0,?,?)")){
            p.setString(1,OFFER);p.setString(2,OFFER);p.setString(3,Instant.now().minusSeconds(60).toString());p.setString(4,Instant.now().toString());p.executeUpdate();
        }
        adapter=new OzonGtinAdapter(shop,transport,id->{});
    }
    @AfterEach void close() {
        if(priorLicense!=null)LicenseRuntime.install(priorLicense);
        if(license!=null)license.close();
        if(priorData==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",priorData);
    }
    private RegisteredGtin card(String gtin,String goodId) {
        return new RegisteredGtin(gtin,"Q47 NAU","Unrelated Catalog color","52",true,true,
                RegisteredGtinProof.source(shop),Instant.now(),new CatalogProductData(Map.of(
                    "@catalog_id",List.of(goodId),"@catalog_status",List.of("published"),
                    "@size_type",List.of("РОССИЯ"),"tnved",List.of("6203420000"),
                    "brand",List.of("Unrelated Catalog brand")),List.of(),List.of()));
    }
    private void saveCatalog(List<RegisteredGtin> rows) {
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,CatalogReadRevision.observed(rows,REVISION));
        CatalogReadRevision.completed(2,REVISION);
    }
    private RegisteredGtin currentCard(){return new GtinSyncRepository().catalogItem(2,Marketplace.OZON,GTIN);}
    private Preview preview()throws Exception {
        return new GtinPreviewService(new GtinSyncRepository(),ignored->adapter).create(key,Operation.ADD,"",GTIN);
    }
    private OzonReviewedGtinLinks.Link link(){return OzonReviewedGtinLinks.read(shop,key);}

    @Test void explicitPreviewMintsANewExactBindingDespiteDifferentColorBrandAndTariff()throws Exception {
        assertNull(link());assertTrue(CatalogReadRevision.complete(2));
        var planned=preview();var saved=link();assertNotNull(saved);
        assertTrue(OzonReviewedVariantProof.exactProof(saved.revision()));
        assertTrue(planned.before().postWriteFingerprint().startsWith("ozon-exact-v1:"));
        assertEquals(GTIN,saved.gtin());assertEquals("9001",saved.sku());
        assertEquals(0,transport.writes.get(),"Preparing a local proof must not send a seller mutation");
    }
    @Test void ordinaryReadAndIdentityValidationCannotMintAnExactBinding()throws Exception {
        var observed=adapter.read(key);assertNull(link());
        assertThrows(IllegalArgumentException.class,()->adapter.validateIdentity(observed,currentCard()));
        assertNull(link());assertEquals(0,transport.writes.get());
    }
    @Test void barcodeReadbackCannotRetroactivelyCreateAPlanOrClaimItWasApplied()throws Exception {
        var before=adapter.read(key);
        var unprepared=new Preview(before,Operation.ADD,"",GTIN,adapter.capability(key));
        transport.card.getAsJsonArray("barcodes").add(GTIN);
        assertFalse(adapter.hasBarcode(key,"9001",GTIN));
        assertEquals(Verification.UNKNOWN,adapter.verify(unprepared,new Submission("")));
        assertNull(link());assertEquals(0,transport.writes.get());
    }
    @Test void successfulResponseRequiresExactReadbackAndPreservesTheOldBarcode()throws Exception {
        var planned=preview();transport.apply=false;var response=adapter.submit(planned);
        assertEquals(1,transport.writes.get());assertEquals(Verification.PENDING,adapter.verify(planned,response));
        assertEquals(List.of("legacy"),adapter.read(key).barcodes());
        transport.card.getAsJsonArray("barcodes").add(GTIN);
        assertEquals(Verification.APPLIED,adapter.verify(planned,response));
        assertTrue(adapter.hasBarcode(key,"9001",GTIN));
        assertEquals(List.of("legacy",GTIN),adapter.read(key).barcodes());
        assertEquals("9001",transport.sent.getAsJsonArray("barcodes").get(0).getAsJsonObject().get("sku").getAsString());
        assertEquals(1,transport.writes.get());
    }
    @Test void exactOwnedModelAndSizeCanBeWrittenAndVerifiedWhenOzonColorIsBlank()throws Exception {
        transport.color="";var planned=preview();
        assertTrue(OzonReviewedVariantProof.exactProof(link().revision()));
        var submitted=adapter.submit(planned);
        assertEquals(Verification.APPLIED,adapter.verify(planned,submitted));
        assertEquals(List.of("legacy",GTIN),adapter.read(key).barcodes());
        assertEquals(1,transport.writes.get());
    }
    @Test void actualVerifiedMappingIsVisibleToTheNativeReaderButCannotMoveToAnotherSkuOrModel()throws Exception {
        var planned=preview();var submitted=adapter.submit(planned);
        assertEquals(Verification.APPLIED,adapter.verify(planned,submitted));
        new GtinSyncRepository().recordVerifiedMapping(key,GTIN);
        var reader=new OzonProductSnapshotReader(ignored->transport);
        var nativeKey=new com.vncode.app.features.products.ProductKey(2,Marketplace.OZON,"501",OFFER);
        var accepted=reader.readForGtinRegistrationProof(shop,nativeKey).variants().getFirst();
        assertTrue(accepted.gtinVerified());assertEquals(GTIN,accepted.gtin());assertTrue(accepted.gtinEvidence().current());
        transport.card.addProperty("sku",9002);
        assertFalse(reader.readForGtinRegistrationProof(shop,nativeKey).variants().getFirst().gtinVerified());
        transport.card.addProperty("sku",9001);String otherModel="B3Q47NAU-XXL";transport.card.addProperty("offer_id",otherModel);
        var changedKey=new com.vncode.app.features.products.ProductKey(2,Marketplace.OZON,"501",otherModel);
        assertFalse(reader.readForGtinRegistrationProof(shop,changedKey).variants().getFirst().gtinVerified());
        assertEquals(1,transport.writes.get());
    }
    @Test void changingNativeSkuDuringLimiterWaitCannotReuseTheExactPlan()throws Exception {
        var planned=preview();var guarded=new OzonGtinAdapter(shop,transport,id->transport.card.addProperty("sku",9002));
        assertThrows(GtinKnownUnsentException.class,()->guarded.submit(planned));assertEquals(0,transport.writes.get());
    }
    @Test void matchingLegacyAttributesCannotHideANativeSkuChangeFromTheReader()throws Exception {
        var old=currentCard();var fields=new LinkedHashMap<>(old.details().fields());
        fields.put("@size_type",List.of("INTERNATIONAL"));
        saveCatalog(List.of(new RegisteredGtin(GTIN,OFFER,"Blue","XXL",true,true,old.source(),Instant.now(),
                new CatalogProductData(fields,List.of(),List.of()))));
        var planned=preview();var submitted=adapter.submit(planned);
        assertEquals(Verification.APPLIED,adapter.verify(planned,submitted));
        new GtinSyncRepository().recordVerifiedMapping(key,GTIN);
        var reader=new OzonProductSnapshotReader(ignored->transport);
        var nativeKey=new com.vncode.app.features.products.ProductKey(2,Marketplace.OZON,"501",OFFER);
        assertTrue(reader.readForGtinRegistrationProof(shop,nativeKey).variants().getFirst().gtinVerified());
        transport.card.addProperty("sku",9002);
        assertFalse(reader.readForGtinRegistrationProof(shop,nativeKey).variants().getFirst().gtinVerified());
        assertEquals(1,transport.writes.get());
    }
    @Test void changingTheFullModelDuringLimiterWaitCannotReuseTheExactPlan()throws Exception {
        var planned=preview();var guarded=new OzonGtinAdapter(shop,transport,id->transport.card.addProperty("offer_id","B3Q47NAU-XXL"));
        assertThrows(GtinKnownUnsentException.class,()->guarded.submit(planned));assertEquals(0,transport.writes.get());
    }
    @Test void changingTheSizePairDuringLimiterWaitCannotReuseTheExactPlan()throws Exception {
        var planned=preview();var guarded=new OzonGtinAdapter(shop,transport,id->{transport.size="L";transport.russianSize="48";});
        assertThrows(GtinKnownUnsentException.class,()->guarded.submit(planned));assertEquals(0,transport.writes.get());
    }
    @Test void newlyDiscoveredCatalogAmbiguityBlocksAnAlreadyPreparedExactPlan()throws Exception {
        var planned=preview();saveCatalog(List.of(currentCard(),card("00000000000024","92")));
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(planned));assertEquals(0,transport.writes.get());
    }
    @Test void selectedSingletonCannotHideAnotherMatchingGtinInTheFullCatalog()throws Exception {
        saveCatalog(List.of(currentCard(),card("00000000000024","92")));
        var error=assertThrows(IllegalStateException.class,this::preview);
        assertEquals("GTIN_AMBIGUOUS",error.getMessage());assertNull(link());assertEquals(0,transport.writes.get());
    }
    @Test void incompleteCatalogCannotAuthorizeAnExactPlan()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM app_config WHERE key='catalog.read.revision.2.complete'");}
        assertFalse(CatalogReadRevision.complete(2));
        assertEquals("SOURCE_SYNC_REQUIRED",assertThrows(IllegalStateException.class,this::preview).getMessage());
        assertNull(link());assertEquals(0,transport.writes.get());
    }
    @Test void gtinAlreadyHeldByAnArchivedNativeProductStillCannotBeAdded()throws Exception {
        transport.foreign=JsonParser.parseString("{\"id\":502,\"offer_id\":\"archived-sibling\",\"sku\":9002,\"barcodes\":[\"00000000000017\"],\"is_archived\":true}").getAsJsonObject();
        transport.foreignArchived=true;
        var error=assertThrows(IllegalArgumentException.class,this::preview);
        assertEquals("gtin_on_another_product",error.getMessage());assertEquals(0,transport.writes.get());
    }
}
