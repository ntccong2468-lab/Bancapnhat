package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonExistingCardLinkServiceTest {
    @TempDir Path root;Shop shop;ProductKey key=new ProductKey(2,Marketplace.OZON,"501","offer-501");
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private OzonExistingCardLinkService.Review review(long id,String revision){return new OzonExistingCardLinkService.Review(key,GtinWriteTestFixture.GTIN,id,"9001","shirt","offer-501","Blue","52","seller article","Blue","52","6109100000",ListingIdentity.fingerprint(shop),RegisteredGtinProof.source(shop),revision,"b".repeat(64));}
    @Test void anExactConfirmedReviewIsPersistedButDoesNotMarkBarcodeApplied(){
        var reviewed=review(91,"a".repeat(64));var service=new OzonExistingCardLinkService((s,k,g)->reviewed);
        assertNull(OzonReviewedGtinLinks.read(shop,key));assertDoesNotThrow(()->service.approve(shop,reviewed));
        var link=OzonReviewedGtinLinks.read(shop,key);assertEquals(91,link.goodId());assertEquals("9001",link.sku());assertEquals(reviewed.revision(),link.revision());
        assertFalse(new com.vncode.app.features.gtinsync.GtinSyncRepository().verifiedMappings(shop).containsKey(key));
    }
    @Test void changedRemoteCardOrProductDuringReviewNeverSavesTheLink(){
        var before=review(91,"a".repeat(64));var changed=new AtomicReference<>(review(92,"a".repeat(64)));var service=new OzonExistingCardLinkService((s,k,g)->changed.get());
        assertEquals("GTIN_REVIEW_CHANGED",assertThrows(IllegalStateException.class,()->service.approve(shop,before)).getMessage());assertNull(OzonReviewedGtinLinks.read(shop,key));
        changed.set(review(91,"c".repeat(64)));assertThrows(IllegalStateException.class,()->service.approve(shop,before));assertNull(OzonReviewedGtinLinks.read(shop,key));
    }
    @Test void sellerScopeChangeInvalidatesTheSavedOwner(){
        var before=review(91,"a".repeat(64));GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        var service=new OzonExistingCardLinkService((s,k,g)->before);assertThrows(SecurityException.class,()->service.approve(shop,before));assertNull(OzonReviewedGtinLinks.read(shop,key));
    }
    private OzonExistingCardLinkService.Review second(){var r=review(92,"b".repeat(64));return new OzonExistingCardLinkService.Review(new ProductKey(2,Marketplace.OZON,"502","offer-502"),"00000000000024",92,"9002",r.ozonTitle(),"different-second-article",r.ozonColor(),r.ozonSize(),r.catalogArticle(),r.catalogColor(),r.catalogSize(),r.tnved(),r.owner(),r.source(),r.revision(),r.catalogRevision());}
    @Test void batchApprovalPersistsEveryReviewedPairWithoutClaimingBarcodeWrites()throws Exception{
        var first=review(91,"a".repeat(64));var second=second();var byKey=java.util.Map.of(first.key(),first,second.key(),second);
        new OzonExistingCardLinkService((s,k,g)->byKey.get(k)).approveBatch(shop,java.util.List.of(first,second));
        assertEquals(first.gtin(),OzonReviewedGtinLinks.read(shop,first.key()).gtin());assertEquals(second.gtin(),OzonReviewedGtinLinks.read(shop,second.key()).gtin());
        assertTrue(new com.vncode.app.features.gtinsync.GtinSyncRepository().verifiedMappings(shop).isEmpty());
    }
    @Test void aLateChangedPairDoesNotSaveTheEarlierPairs()throws Exception{
        var first=review(91,"a".repeat(64));var second=second();
        var service=new OzonExistingCardLinkService((s,k,g)->k.equals(first.key())?first:review(93,"c".repeat(64)));
        assertThrows(IllegalStateException.class,()->service.approveBatch(shop,java.util.List.of(first,second)));
        assertNull(OzonReviewedGtinLinks.read(shop,first.key()));assertNull(OzonReviewedGtinLinks.read(shop,second.key()));
    }
    @Test void aDatabaseGtinConflictRollsBackTheWholeApprovalBatch()throws Exception{
        var first=review(91,"a".repeat(64));var second=second();var existing=new ProductKey(2,Marketplace.OZON,"503","already-owned");
        OzonReviewedGtinLinks.save(shop,existing,new OzonReviewedGtinLinks.Link(second.gtin(),92,"9003",second.owner(),second.source(),second.revision()));
        var byKey=java.util.Map.of(first.key(),first,second.key(),second);
        assertThrows(IllegalStateException.class,()->new OzonExistingCardLinkService((s,k,g)->byKey.get(k)).approveBatch(shop,java.util.List.of(first,second)));
        assertNull(OzonReviewedGtinLinks.read(shop,first.key()));assertNull(OzonReviewedGtinLinks.read(shop,second.key()));assertEquals(second.gtin(),OzonReviewedGtinLinks.read(shop,existing).gtin());
    }

}
