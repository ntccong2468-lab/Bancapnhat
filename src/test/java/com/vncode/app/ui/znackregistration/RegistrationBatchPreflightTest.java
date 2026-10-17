package com.vncode.app.ui.znackregistration;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.listing.ListingIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RegistrationBatchPreflightTest {
    @TempDir Path directory;
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    @Test void missingFactsAreGroupedBeforeAllocationAndTheReviewDoesNotChangeTheSource()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"101","201");
        var snapshot=new ProductSnapshot(key,"live:fixture","Костюм","","","Article","1",List.of(),List.of(),List.of(new ProductVariant("201","Article","Brown","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        var repository=new ProductRepository();repository.saveVerifiedSnapshot(shop,snapshot);
        var review=RegistrationBatchPreflight.read(shop,List.of(key));assertEquals(Set.of("brand","material","gender","tnved"),review.findings().stream().map(RegistrationBatchPreflight.Finding::field).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(key),review.keys("brand"));assertEquals(snapshot,repository.localSnapshot(key).orElseThrow());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM znack_card_registrations")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
}
