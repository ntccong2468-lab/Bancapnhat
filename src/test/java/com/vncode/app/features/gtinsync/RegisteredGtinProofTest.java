package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static com.vncode.app.features.gtinsync.GtinWriteTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredGtinProofTest {
    @TempDir Path root;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void freshPublishedTradeUnitBelongsToExactTestedEnterpriseEnvironmentAndCredentials()throws Exception {
        var source=RegisteredGtinProof.source(shop);assertTrue(source.matches("National Catalog@[a-f0-9]{64}"));
        RegisteredGtinProof.requireCurrent(shop,GTIN);
        saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertNotEquals(source,RegisteredGtinProof.source(shop));assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
        saveSettings(shop,CERTIFICATE,"https://sandbox.example.invalid/api/v3/true-api");
        assertNotEquals(source,RegisteredGtinProof.source(shop));assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
    }
    @Test void staleUnpublishedOrBoxRowsDoNotAuthorizeWrites() {
        var source=RegisteredGtinProof.source(shop);var repo=new GtinSyncRepository();
        var evidence=observed(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")));
        for(var row:List.of(new RegisteredGtin(GTIN,"","","",true,true,source,Instant.now().minusSeconds(86401),evidence),new RegisteredGtin(GTIN,"","","",false,true,source,Instant.now(),evidence),new RegisteredGtin(GTIN,"","","",true,false,source,Instant.now(),evidence))){
            repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(row));assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
        }
    }
    @Test void expiredOrUntestedCertificateCannotProduceScope() {
        var repository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));var settings=repository.getSettings();
        assertThrows(IllegalStateException.class,()->RegisteredGtinProof.scope(shop,settings,Instant.now().plusSeconds(86401)));
        assertThrows(IllegalStateException.class,()->RegisteredGtinProof.scope(shop,ZnackModels.Settings.empty(),Instant.now()));
        assertFalse(RegisteredGtinProof.current(shop).toString().contains("7700000000"));
    }
    @Test void normalizedLeadingZerosNeverLoseUnrelatedBarcodes() {
        assertTrue(RegisteredGtinProof.sameCode("00000017",GTIN));assertFalse(RegisteredGtinProof.sameCode("legacy",GTIN));
    }
    @Test void publicationFlagWithoutExactRemoteIdentityAndRawStatusCannotAuthorizeWrites(){
        var source=RegisteredGtinProof.source(shop);var repository=new GtinSyncRepository();
        for(var fields:List.<java.util.Map<String,List<String>>>of(java.util.Map.of(),java.util.Map.of("@catalog_id",List.of("91")),java.util.Map.of("@catalog_id",List.of("0"),"@catalog_status",List.of("published")),java.util.Map.of("@catalog_id",List.of("91.5"),"@catalog_status",List.of("published")),java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("draft")))){
            repository.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin(GTIN,"Fixture","","",true,true,source,Instant.now(),observed(fields))));
            assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,List.of(GTIN)));
        }
    }
    @Test void rawPublishedIdentityWorksForSingleAndBatchProofWithNoInventedPermitRequirement(){
        RegisteredGtinProof.requireCurrent(shop,GTIN);assertEquals(GTIN,RegisteredGtinProof.requireCurrent(shop,List.of(GTIN)).get(GTIN).gtin());
    }
    @Test void existingPermitCannotBeUsedWhileItsExplicitRegistryCheckIsUnavailable(){
        var fields=java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"@documents_status",List.of("REGISTRY_DOCUMENTS_UNAVAILABLE"));
        var document=new com.vncode.app.features.catalogsync.CatalogProductData.Document("DECLARATION","FIXTURE-PERMIT","2026-01-01","2029-01-01",false,"",java.util.Map.of());
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of(document));
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin(GTIN,"Fixture","","",true,true,RegisteredGtinProof.source(shop),Instant.now(),details)));
        assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
    }
    @Test void aFreshCardOnlyReadCannotSilentlyEraseKnownSameSourcePermitUncertainty(){
        var source=RegisteredGtinProof.source(shop);var fields=java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"@documents_status",List.of("REGISTRY_DOCUMENTS_PENDING"));
        var permit=new com.vncode.app.features.catalogsync.CatalogProductData.Document("DECLARATION","FIXTURE-PERMIT","2026-01-01","2029-01-01",false,"",java.util.Map.of());
        var prior=new RegisteredGtin(GTIN,"Fixture","","",true,true,source,Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of(permit)));
        var fresh=new RegisteredGtin(GTIN,"Fixture","","",true,true,source,Instant.now(),observed(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"))));
        var error=assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireKnownDocumentState(List.of(prior),List.of(fresh)));assertEquals("REGISTRY_DOCUMENTS_PENDING",error.getMessage());assertEquals("REGISTRY_DOCUMENTS_PENDING",prior.details().single("@documents_status"));
        var anotherScope=new RegisteredGtin(GTIN,"Fixture","","",true,true,"National Catalog@different",Instant.now(),fresh.details());assertDoesNotThrow(()->RegisteredGtinProof.requireKnownDocumentState(List.of(prior),List.of(anotherScope)));
    }
    @Test void anAbsentInlinePermitDoesNotInventAMandatoryDocumentRequirement(){
        var source=RegisteredGtinProof.source(shop);var fields=java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"@documents_status",List.of("REGISTRY_DOCUMENTS_UNAVAILABLE"));
        var prior=new RegisteredGtin(GTIN,"Fixture","","",true,true,source,Instant.now(),observed(fields));
        var fresh=new RegisteredGtin(GTIN,"Fixture","","",true,true,source,Instant.now(),observed(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"))));
        assertDoesNotThrow(()->RegisteredGtinProof.requireKnownDocumentState(List.of(prior),List.of(fresh)));
    }
    @Test void configuredGoodsOwnerCannotChangeToAnotherEnterpriseUnderExistingProof() {
        var repository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));var settings=repository.getSettings();
        var changed=new ZnackModels.Settings(settings.trueApiBaseUrl(),"","","",settings.participantInn(),"","7800000000","",settings.signerCertificate(),"[]","","","",false,"","[]",settings.certificateMetadataJson(),settings.signerTestedAt());
        repository.saveSettings(changed);assertThrows(IllegalStateException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
    }
    @Test void sandboxAndUnverifiedOriginsCannotAuthorizeProductionSellerWrite() {
        for(String base:List.of("https://sandbox.example.invalid/api/v3/true-api","https://markirovka.crpt.ru.evil.invalid/api/v3/true-api","http://markirovka.crpt.ru/api/v3/true-api")){
            saveSettings(shop,CERTIFICATE,base);catalog(shop);
            assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
        }
    }
    @Test void ownerFailureNamesTheFailedConditionWithoutDisclosingCertificateOrAccountValues() {
        var base=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings();var now=Instant.now();
        var metadata=com.google.gson.JsonParser.parseString(base.certificateMetadataJson()).getAsJsonObject();
        record Case(RegisteredGtinProof.OwnerFailure expected,ZnackModels.Settings settings){}
        var cases=new java.util.ArrayList<Case>();
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.SIGNATURE_NOT_TESTED,copy(base,base.participantInn(),"",CERTIFICATE,metadata.toString(),null)));
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.SIGNATURE_TIME_INVALID,copy(base,base.participantInn(),"",CERTIFICATE,metadata.toString(),now.plusSeconds(301))));
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.METADATA_INVALID,copy(base,base.participantInn(),"",CERTIFICATE,"{}",now)));
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.CERTIFICATE_CHANGED,copy(base,base.participantInn(),"","B".repeat(40),metadata.toString(),now)));
        var noPrivate=metadata.deepCopy();noPrivate.addProperty("hasPrivateKey",false);
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.PRIVATE_KEY_UNAVAILABLE,copy(base,base.participantInn(),"",CERTIFICATE,noPrivate.toString(),now)));
        var future=metadata.deepCopy();future.addProperty("validFrom",now.plusSeconds(60).toString());
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.CERTIFICATE_NOT_YET_VALID,copy(base,base.participantInn(),"",CERTIFICATE,future.toString(),now)));
        var expired=metadata.deepCopy();expired.addProperty("validTo",now.minusSeconds(1).toString());
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.CERTIFICATE_EXPIRED,copy(base,base.participantInn(),"",CERTIFICATE,expired.toString(),now)));
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.PARTICIPANT_MISMATCH,copy(base,"7800000000","",CERTIFICATE,metadata.toString(),now)));
        cases.add(new Case(RegisteredGtinProof.OwnerFailure.GOODS_OWNER_MISMATCH,copy(base,base.participantInn(),"7800000000",CERTIFICATE,metadata.toString(),now)));
        for(var sample:cases){
            var error=assertThrows(RegisteredGtinProof.UnverifiedOwnerException.class,()->RegisteredGtinProof.scope(shop,sample.settings(),now));
            assertEquals(sample.expected(),error.reason());assertFalse(error.toString().contains("7800000000"));assertFalse(error.toString().contains(CERTIFICATE));
        }
    }
    private static ZnackModels.Settings copy(ZnackModels.Settings base,String participant,String owner,String selector,String metadata,Instant tested){
        return new ZnackModels.Settings(base.trueApiBaseUrl(),base.suzBaseUrl(),base.omsId(),base.omsConnection(),participant,base.producerInn(),owner,base.signerExecutable(),selector,base.signerArgumentsJson(),base.documentNumber(),base.documentDate(),base.pdfFolder(),base.autoIntroduction(),base.certificateListExecutable(),base.certificateListArgumentsJson(),metadata,tested);
    }
    private static com.vncode.app.features.catalogsync.CatalogProductData observed(java.util.Map<String,List<String>> fields){return new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of());}
}
