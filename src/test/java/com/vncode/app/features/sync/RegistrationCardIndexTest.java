package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.vncode.app.models.Shop;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationCardIndexTest {
    @TempDir Path directory;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);RegistrationSourceFixture.ready(shop);}
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    RegisteredGtin card(String gtin,boolean published){return new RegisteredGtin(gtin,"A","Blue","M",true,published,RegisteredGtinProof.source(shop),Instant.now());}
    RegistrationCardIndex index(RegisteredGtin... cards){new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(cards));return new RegistrationCardIndex(shop);}
    @Test void publishedExistingCatalogCardIsDisplayedWithoutCreatingANativeCheckpoint(){
        var view=index(card(GtinWriteTestFixture.GTIN,true)).view(Status.NOT_CREATED,"",List.of(GtinWriteTestFixture.GTIN),"A","Blue","M",true);
        assertTrue(view.published());assertTrue(view.exists());assertFalse(view.blocked());assertEquals("status.published",view.status());
        try(var c=Database.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM znack_card_registrations")){assertEquals(0,rows.getInt(1));}catch(Exception e){throw new AssertionError(e);}
    }
    @Test void missingCardStaysEligibleForExplicitCreation(){var view=index().view(Status.NOT_CREATED,"",List.of(),"A","Blue","M",true);assertFalse(view.exists());assertFalse(view.blocked());assertEquals("status.not_created",view.status());}
    @Test void localPublishedWithoutCatalogProofIsNotPresentedAsPublished(){var view=index().view(Status.PUBLISHED,GtinWriteTestFixture.GTIN,List.of(),"A","Blue","M",true);assertFalse(view.published());assertTrue(view.blocked());assertEquals("SOURCE_PUBLISHED_NOT_CONFIRMED",view.detail());}
    @Test void draftCatalogCardDoesNotMasqueradeAsMissingOrPublished(){var view=index(card(GtinWriteTestFixture.GTIN,false)).view(Status.NOT_CREATED,"",List.of(),"A","Blue","M",true);assertFalse(view.published());assertTrue(view.exists());assertTrue(view.blocked());assertEquals("source.unpublished",view.status());}
    @Test void ownedModerationCheckpointKeepsItsActionableNativeStatus(){var view=index(card(GtinWriteTestFixture.GTIN,false)).view(Status.READY_TO_SIGN,GtinWriteTestFixture.GTIN,List.of(),"A","Blue","M",true);assertEquals("status.ready_to_sign",view.status());assertFalse(view.blocked());}
    @Test void allocationAndLocalPreflightDoNotProveSubmissionButKeepOwnershipGuards(){
        var cards=index(card(GtinWriteTestFixture.GTIN,false));
        assertTrue(RegistrationCardIndex.notSubmitted(Status.READY_TO_SUBMIT,GtinWriteTestFixture.GTIN,null,"",false));
        assertTrue(cards.view(Status.READY_TO_SUBMIT,GtinWriteTestFixture.GTIN,List.of(),"A","Blue","M",true).exists(),"Presentation eligibility must not erase the native identity used by ownership and duplicate guards");
        assertTrue(cards.view(Status.READY_TO_SUBMIT,GtinWriteTestFixture.GTIN,List.of(),"A","Blue","M",false).blocked());
        assertEquals("source.unpublished",cards.view(Status.CHECKING,"",List.of(GtinWriteTestFixture.GTIN),"A","Blue","M",true).status(),"A matching preexisting Catalog card cannot become a fresh create just because local preflight started");
        for(var uncertain:List.of(Status.GTIN_GENERATED,Status.FEED_SUBMITTING,Status.FEED_REVIEW_REQUIRED,Status.ALLOCATING_GTIN)){
            assertFalse(RegistrationCardIndex.notSubmitted(uncertain,GtinWriteTestFixture.GTIN,null,"",false));
            assertFalse(RegistrationCardIndex.confirmedSubmission(uncertain,""));
        }
        assertFalse(RegistrationCardIndex.confirmedSubmission(Status.PROCESSING,""));
        assertTrue(RegistrationCardIndex.confirmedSubmission(Status.FEED_SUBMITTED,"accepted-feed"));
        assertFalse(RegistrationCardIndex.notSubmitted(Status.READY_TO_SUBMIT,GtinWriteTestFixture.GTIN,123L,"",false));
        assertFalse(RegistrationCardIndex.notSubmitted(Status.READY_TO_SUBMIT,GtinWriteTestFixture.GTIN,null,"accepted-feed",false));
    }
    @Test void externalCatalogErrorsModerationAndDraftsRetainTheirActualDistinctStates(){
        for(var status:List.of("errors","moderation","draft")){
            var base=card(GtinWriteTestFixture.GTIN,false);
            var data=com.vncode.app.integration.znack.registration.CatalogProductDataParser.parse(com.google.gson.JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\""+status+"\"]}").getAsJsonObject());
            var observed=new RegisteredGtin(base.gtin(),base.article(),base.color(),base.size(),base.tradeUnit(),false,base.source(),base.syncedAt(),data);
            var cards=index(observed);var view=cards.view(Status.NOT_CREATED,"",List.of(base.gtin()),"A","Blue","M",true);
            assertEquals("source."+status,view.status());assertFalse(view.published());assertTrue(view.blocked());assertTrue(view.exists());
            assertEquals("status.ready_to_sign",cards.view(Status.READY_TO_SIGN,base.gtin(),List.of(),"A","Blue","M",true).status(),"An old source receipt never replaces an actionable newer native checkpoint");
        }
    }
    @Test void ambiguousAttributeMatchAndConflictingVariantAreBlocked(){
        var cards=index(card(GtinWriteTestFixture.GTIN,true),card("00000000000024",true));
        assertTrue(cards.view(Status.NOT_CREATED,"",List.of(),"A","Blue","M",true).blocked());
        assertEquals("GTIN_VARIANT_CONFLICT",cards.view(Status.NOT_CREATED,"",List.of(GtinWriteTestFixture.GTIN),"A","Red","M",true).detail());
    }
    @Test void aForeignNativeCheckpointCannotEnableResumeOrSign(){assertTrue(index(card(GtinWriteTestFixture.GTIN,true)).view(Status.READY_TO_SIGN,GtinWriteTestFixture.GTIN,List.of(),"A","Blue","M",false).blocked());}
    @Test void scopedOldCatalogRowsCannotAuthorizeStatus(){var old=new RegisteredGtin(GtinWriteTestFixture.GTIN,"A","Blue","M",true,true,"National Catalog@foreign",Instant.now());assertFalse(index(old).view(Status.NOT_CREATED,"",List.of(),"A","Blue","M",true).exists());}
    @Test void wbPublishedRussianSizeUsesItsObservedWbSizeAndCannotGuessAConversion(){
        var base=card(GtinWriteTestFixture.GTIN,true);var russian=new RegisteredGtin(base.gtin(),"A","Blue","54",true,true,base.source(),base.syncedAt(),new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@size_type",List.of("РОССИЯ")),List.of(),List.of()));
        var cards=index(russian);assertTrue(cards.view(Status.NOT_CREATED,"",List.of(base.gtin()),"A","Blue","3XL","54",true).published());
        assertTrue(cards.view(Status.NOT_CREATED,"",List.of(),"A","Blue","3XL","54",true).published());
        assertTrue(cards.view(Status.NOT_CREATED,"",List.of(base.gtin()),"A","Blue","3XL","52",true).blocked());
        assertTrue(cards.view(Status.NOT_CREATED,"",List.of(base.gtin()),"A","Blue","3XL",true).blocked());
    }
    com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku sku(String article,String color,String size,List<String> barcodes,String gtin){return new com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku(101,201,1,article,"Одежда","Brand","Name",color,size,barcodes,"",false,gtin,null,"",Status.NOT_CREATED,"",false,"");}
    @Test void displayProofRequiresActualCatalogObservationRatherThanBarcodeLengthOrLocalAllocation(){
        var cards=index();assertTrue(cards.verifiedWb(sku("A","Blue","M",List.of("4006381333931"),"")).isEmpty(),"A valid EAN13 checksum is not Catalog registration proof");
        assertTrue(cards.verifiedWb(sku("A","Blue","M",List.of(),"00000000000024")).isEmpty(),"An allocated local code is not a remotely observed card");
        var actual=card(GtinWriteTestFixture.GTIN,true);cards=index(actual);var raw=List.of(actual.gtin().substring(1));var selected=sku("A","Blue","M",raw,"");
        assertEquals(actual,cards.verifiedWb(selected).orElseThrow());assertEquals(raw,selected.barcodes(),"Equivalent code display must preserve the original barcode's leading zeroes");
    }
    @Test void verifiedDisplayRejectsWrongArticleAndMissingOrConflictingVariantEvenForAnExactBarcode(){
        var actual=card(GtinWriteTestFixture.GTIN,true);var cards=index(actual);var raw=List.of(actual.gtin());
        assertTrue(cards.verifiedWb(sku("Different Article","Blue","M",raw,"")).isEmpty());
        assertTrue(cards.verifiedWb(sku("A","","M",raw,"")).isEmpty());assertTrue(cards.verifiedWb(sku("A","Blue","",raw,"")).isEmpty());
        assertTrue(cards.verifiedWb(sku("A","Red","M",raw,"")).isEmpty());assertTrue(cards.verifiedWb(sku("A","Blue","L",raw,"")).isEmpty());
        var conflict=cards.view(Status.NOT_CREATED,"",raw,"Different Article","Blue","M",true);
        assertEquals("source.ambiguous",conflict.status());assertEquals("GTIN_VARIANT_CONFLICT",conflict.detail());assertFalse(conflict.published());assertTrue(conflict.blocked());assertTrue(conflict.exists());
    }
    @Test void missingVariantFactsCannotPresentPublishedCardAsVerified(){
        var actual=card(GtinWriteTestFixture.GTIN,true);var cards=index(actual);
        for(var missing:List.of(List.of("","M"),List.of("Blue",""))){
            var view=cards.view(Status.NOT_CREATED,"",List.of(actual.gtin()),"A",missing.get(0),missing.get(1),true);
            assertEquals("source.unverified",view.status());assertEquals("GTIN_VARIANT_UNVERIFIED",view.detail());assertTrue(view.exists());assertTrue(view.blocked());assertFalse(view.published());
        }
        var missingCatalogColor=new RegisteredGtin(actual.gtin(),actual.article(),"",actual.size(),true,true,actual.source(),actual.syncedAt());
        assertEquals("source.unverified",index(missingCatalogColor).view(Status.NOT_CREATED,"",List.of(actual.gtin()),"A","Blue","M",true).status());
        var pending=cards.view(Status.PROCESSING,actual.gtin(),List.of(),"A","","M",true);
        assertEquals("status.processing",pending.status());assertTrue(pending.blocked());assertFalse(pending.published());
    }
    @Test void missingVariantFactsPreserveRemoteDraftModerationErrorsAndOwnedPendingCheckpoint(){
        for(var remote:List.of("draft","moderation","errors")){
            var base=card(GtinWriteTestFixture.GTIN,false);
            var details=com.vncode.app.integration.znack.registration.CatalogProductDataParser.parse(com.google.gson.JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\""+remote+"\"]}").getAsJsonObject());
            var observed=new RegisteredGtin(base.gtin(),base.article(),base.color(),base.size(),true,false,base.source(),base.syncedAt(),details);var cards=index(observed);
            var sourceView=cards.view(Status.NOT_CREATED,"",List.of(base.gtin()),"A","","M",true);
            assertEquals("source."+remote,sourceView.status());assertEquals("GTIN_VARIANT_UNVERIFIED",sourceView.detail());assertTrue(sourceView.exists());assertTrue(sourceView.blocked());assertFalse(sourceView.published());
            for(var state:List.of(Status.FEED_SUBMITTED,Status.PROCESSING,Status.READY_TO_SIGN,Status.SIGNING)){
                var nativeView=cards.view(state,base.gtin(),List.of(),"A","Blue","",true);
                assertEquals("status."+state.name().toLowerCase(Locale.ROOT),nativeView.status());assertEquals("GTIN_VARIANT_UNVERIFIED",nativeView.detail());assertTrue(nativeView.exists());assertTrue(nativeView.blocked());assertFalse(nativeView.published());
            }
        }
    }
    @Test void displayCannotUseStaleOrForeignProofAndDoesNotEraseThatHistory(){
        for(var observed:List.of(new RegisteredGtin(GtinWriteTestFixture.GTIN,"A","Blue","M",true,true,RegisteredGtinProof.source(shop),Instant.now().minusSeconds(86401)),new RegisteredGtin(GtinWriteTestFixture.GTIN,"A","Blue","M",true,true,"National Catalog@foreign",Instant.now()))){
            var cards=index(observed);assertTrue(cards.verifiedWb(sku("A","Blue","M",List.of(observed.gtin()),"")).isEmpty());assertEquals(observed.source(),new GtinSyncRepository().catalogItem(shop.getId(),shop.getMarketplace(),observed.gtin()).source());
        }
    }
    @Test void establishedOzonSellerSkuProofRequiresUniqueModelColorAndExplicitSize()throws Exception{
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);RegistrationSourceFixture.ready(ozon);
        var detail=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@size_type",List.of("РОССИЯ"),"title",List.of("Костюм спортивный"),"brand",List.of("DASHOA")),List.of(),List.of());
        var actual=new RegisteredGtin(GtinWriteTestFixture.GTIN,"152 nau dashoa","Коричневый","52",true,true,RegisteredGtinProof.source(ozon),Instant.now(),detail);
        new GtinSyncRepository().saveCatalog(ozon.getId(),ozon.getMarketplace(),List.of(actual));
        var row=new com.vncode.app.features.products.ProductRow(new com.vncode.app.features.products.ProductKey(ozon.getId(),ozon.getMarketplace(),"501","152DASH-52"),"Костюм спортивный","152DASH-52","501","Костюмы","DASHOA","Brown","XL",List.of(),"","","","",com.vncode.app.features.products.DocumentState.MISSING,"Мужской",true,"52","XL");
        assertEquals(actual,new RegistrationCardIndex(ozon).verifiedOzon(row,Status.NOT_CREATED,"",true).orElseThrow());
        var duplicate=new RegisteredGtin("00000000000024",actual.article(),actual.color(),actual.size(),true,true,actual.source(),Instant.now(),detail);
        new GtinSyncRepository().saveCatalog(ozon.getId(),ozon.getMarketplace(),List.of(actual,duplicate));
        assertEquals(2,new GtinSyncRepository().catalog(ozon.getId(),ozon.getMarketplace()).size(),"Both candidate cards must exist in the same observed Catalog snapshot");
        assertTrue(new RegistrationCardIndex(ozon).verifiedOzon(row,Status.NOT_CREATED,"",true).isEmpty());
    }

}
