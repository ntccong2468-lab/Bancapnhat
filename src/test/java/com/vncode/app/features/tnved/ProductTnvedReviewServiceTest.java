package com.vncode.app.features.tnved;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductTnvedReviewServiceTest {
    @TempDir Path root;Shop shop=new Shop(1,"Owner","fixture");
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Owner','fixture','WILDBERRIES')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductKey key(String card,String size){return new ProductKey(1,Marketplace.WILDBERRIES,card,size);}
    void cache(ProductKey key,String material){new ProductRepository().saveVerifiedSnapshot(shop,new ProductSnapshot(key,"live:own","Костюм спортивный","","Brand","own","9",List.of(),List.of(new ProductAttribute("7","Состав",List.of(material),""),new ProductAttribute("8","Пол",List.of("Мужской"),""),new ProductAttribute("9","Тип ткани",List.of("Трикотаж"),"")),List.of(),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop))));}
    @Test void eachProductReceivesItsOwnCodeAndSizesOfOneCardAgree(){
        var first=key("100","1");var secondSize=key("100","2");var second=key("200","3");cache(first,"хлопок 90%, полиэстер 10%");cache(second,"полиэстер 95%, эластан 5%");
        var review=new ProductTnvedReviewService().review(shop,List.of(first,secondSize,second));assertEquals(2,review.proposals().size());assertEquals(3,review.values().size());assertEquals(List.of("6112110000"),review.values().get(first));assertEquals(review.values().get(first),review.values().get(secondSize));assertEquals(List.of("6112120000"),review.values().get(second));
        assertEquals(3,review.expectedRevisions().size());assertEquals(review.expectedRevisions().get(first),review.expectedRevisions().get(secondSize));
    }
    @Test void anUnavailableProductPreventsAnIncompleteAutomaticBatch(){
        var first=key("100","1");cache(first,"хлопок");var review=new ProductTnvedReviewService().review(shop,List.of(first,key("missing","")));assertTrue(review.values().isEmpty());assertTrue(review.proposals().stream().anyMatch(p->p.reason().equals("unloaded")));
        assertThrows(SecurityException.class,()->new ProductTnvedReviewService().review(shop,List.of(new ProductKey(2,Marketplace.WILDBERRIES,"100","1"))));
    }
    @Test void legacySnapshotWithoutVerifiedOwnerCannotAuthorizeATariffProposal()throws Exception{
        var first=key("100","1");cache(first,"хлопок");
        try(var connection=Database.getConnection();var statement=connection.createStatement()){statement.executeUpdate("UPDATE product_snapshots SET payload=json_remove(payload,'$.metadata.owner')");}
        var review=new ProductTnvedReviewService().review(shop,List.of(first));assertTrue(review.values().isEmpty());assertTrue(review.expectedRevisions().isEmpty());assertEquals("unloaded",review.proposals().getFirst().reason());
    }
}
