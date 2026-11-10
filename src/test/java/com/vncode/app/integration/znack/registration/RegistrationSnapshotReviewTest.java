package com.vncode.app.integration.znack.registration;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class RegistrationSnapshotReviewTest {
    @TempDir Path directory;Shop shop;
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    ProductKey key(String card,String variant){return new ProductKey(shop.getId(),shop.getMarketplace(),card,variant);}
    ProductSnapshot snapshot(ProductKey key){return new ProductSnapshot(key,"live:fresh","Name","","Brand","Article","1",List.of(),List.of(),List.of(new ProductVariant("201","Article","Brown","52","",false),new ProductVariant("202","Article","Brown","54","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));}
    @Test void missingLocalSnapshotsAreReadFreshAndAllVariantSnapshotsSurviveDeduplication()throws Exception{
        var reads=new AtomicInteger();var first=key("101","201");var second=key("101","202");assertTrue(new ProductRepository().localSnapshot(first).isEmpty());
        var service=new RegistrationSnapshotReview((owner,key)->{reads.incrementAndGet();return snapshot(key);});var result=service.read(shop,List.of(first,second,first));
        assertEquals(1,reads.get());assertEquals(List.of(first,second),result.stream().map(ProductSnapshot::key).toList());
        for(var key:List.of(first,second))assertEquals(key,new ProductRepository().localSnapshot(key).orElseThrow().key());
    }
    @Test void eachDifferentProductIsReadAndUnavailableProductNeverLooksLikeAnEmptySuccessfulReview(){
        var reads=new AtomicInteger();var service=new RegistrationSnapshotReview((owner,key)->{if(reads.incrementAndGet()==2)throw new java.io.IOException("fixture offline");return snapshot(key);});
        assertThrows(java.io.IOException.class,()->service.read(shop,List.of(key("101","201"),key("102","201"))));assertEquals(2,reads.get());
    }
    @Test void aVariantAbsentFromTheFreshCardCannotReuseAnotherSizesFacts(){
        var service=new RegistrationSnapshotReview((owner,key)->snapshot(key));assertThrows(SecurityException.class,()->service.read(shop,List.of(key("101","201"),key("101","999"))));
    }
    @Test void aFreshResponseFromAnotherOwnerIsRejectedBeforePersisting(){
        var service=new RegistrationSnapshotReview((owner,key)->{var s=snapshot(key);return new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),Map.of("owner","foreign"));});
        var key=key("101","201");assertThrows(SecurityException.class,()->service.read(shop,List.of(key)));assertTrue(new ProductRepository().localSnapshot(key).isEmpty());
    }
    @Test void oneMissingCardInThreeHundredIsReportedWithoutDiscardingTheOtherVerifiedCards()throws Exception{
        var keys=new ArrayList<ProductKey>();for(int n=0;n<300;n++)keys.add(key(Integer.toString(1000+n),"201"));
        var reads=new AtomicInteger();var service=new RegistrationSnapshotReview((owner,key)->{
            reads.incrementAndGet();if(key.equals(keys.get(137)))throw new com.vncode.app.integration.wb.WbProductSnapshotReader.CardReadException("WB_CARD_NOT_FOUND",key,List.of());
            return snapshot(key);
        });
        var result=service.readAvailable(shop,keys);
        assertEquals(300,reads.get());assertEquals(299,result.snapshots().size());assertEquals(1,result.problems().size());
        assertEquals(keys.get(137),result.problems().getFirst().key());assertEquals("WB_CARD_NOT_FOUND",result.problems().getFirst().code());
        assertEquals(keys.stream().filter(k->!k.equals(keys.get(137))).toList(),result.snapshots().stream().map(ProductSnapshot::key).toList());
        assertTrue(new ProductRepository().localSnapshot(keys.get(137)).isEmpty());assertTrue(new ProductRepository().localSnapshot(keys.getLast()).isPresent());
    }
    @Test void unavailableCardIsReadOnceAndEachOfItsSelectedSizesKeepsItsOwnFailure()throws Exception{
        var first=key("101","201");var second=key("101","202");var good=key("102","201");var reads=new AtomicInteger();
        var result=new RegistrationSnapshotReview((owner,key)->{reads.incrementAndGet();if(key.cardId().equals("101"))throw new com.vncode.app.integration.wb.WbProductSnapshotReader.CardReadException("WB_CARD_INCOMPLETE",key,List.of("dimensions"));return snapshot(key);}).readAvailable(shop,List.of(first,second,good,first));
        assertEquals(2,reads.get());assertEquals(List.of(first,second),result.problems().stream().map(RegistrationSnapshotReview.Problem::key).toList());
        assertTrue(result.problems().stream().allMatch(p->p.missingFields().equals(List.of("dimensions"))));assertEquals(List.of(good),result.snapshots().stream().map(ProductSnapshot::key).toList());
    }
    @Test void absentSelectedVariantDoesNotExcludeItsValidSiblingOrPersistWrongVariant()throws Exception{
        var missing=key("101","999");var valid=key("101","201");
        var result=new RegistrationSnapshotReview((owner,key)->snapshot(key)).readAvailable(shop,List.of(missing,valid));
        assertEquals(List.of(valid),result.snapshots().stream().map(ProductSnapshot::key).toList());
        assertEquals("WB_VARIANT_NOT_FOUND",result.problems().getFirst().code());assertEquals(missing,result.problems().getFirst().key());
        assertTrue(new ProductRepository().localSnapshot(missing).isEmpty());assertTrue(new ProductRepository().localSnapshot(valid).isPresent());
    }
    @Test void interruptedAndNetworkReadsAreNotConvertedIntoProductErrors(){
        for(var failure:List.of(new java.io.IOException("fixture network unavailable"),new java.io.InterruptedIOException("fixture cancelled"))){
            var reads=new AtomicInteger();var service=new RegistrationSnapshotReview((owner,key)->{reads.incrementAndGet();throw failure;});
            assertSame(failure,assertThrows(java.io.IOException.class,()->service.readAvailable(shop,List.of(key("101","201"),key("102","201")))));assertEquals(1,reads.get());
        }
    }
    @Test void partialPreparationStillStopsOnForeignOwner(){
        var service=new RegistrationSnapshotReview((owner,key)->{var s=snapshot(key);return new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),Map.of("owner","foreign"));});
        assertThrows(SecurityException.class,()->service.readAvailable(shop,List.of(key("101","201"),key("102","201"))));
    }
    @Test void strictReadersStillRejectCardLocalFailureInsteadOfReturningAPartialReview(){
        var failed=key("101","201");var failure=new com.vncode.app.integration.wb.WbProductSnapshotReader.CardReadException("WB_CARD_NOT_FOUND",failed,List.of());
        assertSame(failure,assertThrows(java.io.IOException.class,()->new RegistrationSnapshotReview((owner,key)->{throw failure;}).read(shop,List.of(failed))));
    }
    @Test void numericWbArticleCannotMasqueradeAsAMissingSiblingChrtId()throws Exception{
        var valid=key("101","201");var absent=key("101","999");
        var result=new RegistrationSnapshotReview((owner,key)->{
            var s=snapshot(key);return new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),"999",s.categoryId(),s.media(),s.attributes(),List.of(new ProductVariant("201","999","Brown","52","",false)),s.dimensions(),s.documents(),s.checkedAt(),s.metadata());
        }).readAvailable(shop,List.of(valid,absent));
        assertEquals(List.of(valid),result.snapshots().stream().map(ProductSnapshot::key).toList());
        assertEquals(List.of(absent),result.problems().stream().map(RegistrationSnapshotReview.Problem::key).toList());assertEquals("WB_VARIANT_NOT_FOUND",result.problems().getFirst().code());
        assertTrue(new ProductRepository().localSnapshot(absent).isEmpty());
    }
}
