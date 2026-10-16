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
}
