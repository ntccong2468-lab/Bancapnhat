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
}
