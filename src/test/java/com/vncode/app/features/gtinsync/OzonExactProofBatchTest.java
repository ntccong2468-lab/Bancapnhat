package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies real local reads and proof results, rather than a timing threshold. */
class OzonExactProofBatchTest {
    @TempDir Path root;Shop shop;
    static final int COUNT=10;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var cards=new ArrayList<GtinSyncModels.RegisteredGtin>();for(int i=0;i<COUNT;i++)cards.add(card(i,i));
        new GtinSyncRepository().saveCatalog(2,Marketplace.OZON,cards);
        for(int i=0;i<COUNT;i++){
            String offer="MODEL"+i+"-2XL",id=Integer.toString(100+i),sku=Integer.toString(2000+i);
            var product=new ProductSnapshot(new ProductKey(2,Marketplace.OZON,id,offer),"live:fixture","Title","","","MODEL"+i,"77:88",List.of(),List.of(),
                    List.of(new ProductVariant(offer,offer,"Black","2XL","",false,"52",List.of("native"),ProductVariant.GtinEvidence.UNVERIFIED)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku",sku));
            new OzonCatalogRepository().upsertPage(2,List.of(new OzonProductDto(id,offer,sku,"Title","","MODEL"+i,"Black","2XL","Vests","",false,Instant.now().minusSeconds(2).toString(),List.of("native"),"77","88","2XL","52")),"");
            new OzonExistingCardLinkService().bindExactUnique(shop,product,cards);
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    GtinSyncModels.RegisteredGtin card(int code,int model){
        String base=String.format(Locale.ROOT,"%013d",100+code);int sum=0;for(int i=0;i<13;i++)sum+=(base.charAt(i)-'0')*(i%2==0?3:1);String gtin=base+((10-sum%10)%10);
        var details=new CatalogProductData(Map.of("@catalog_id",List.of(Integer.toString(900+code)),"@catalog_status",List.of("published"),"tnved",List.of("6112110000")),List.of(),
                List.of(new CatalogProductData.Document("DECLARATION","FIXTURE","2020-01-01","2099-12-31",false,"",Map.of("registryStatus","active"))));
        return new GtinSyncModels.RegisteredGtin(gtin,"MODEL"+model,"Blue","XXL",true,true,RegisteredGtinProof.source(shop),Instant.now(),details);
    }
    @Test void eachBatchLoadsTheOwnedCatalogOnceAndANewCallSeesNewAmbiguity()throws Exception{
        var loads=new AtomicInteger();
        OzonReviewedVariantProof.ExactCatalogLoader loader=(c,s)->{loads.incrementAndGet();return OzonReviewedVariantProof.readExactCatalog(c,s);};
        try(var c=Database.getConnection()){
            var first=OzonReviewedVariantProof.claims(c,shop,true,loader);assertEquals(COUNT,first.size());assertTrue(first.stream().allMatch(claim->claim.metadata()!=null));assertEquals(1,loads.get());
        }
        new GtinSyncRepository().mergeCatalog(2,Marketplace.OZON,List.of(card(100,0)));
        try(var c=Database.getConnection()){
            var second=OzonReviewedVariantProof.claims(c,shop,true,loader);assertEquals(COUNT,second.size());assertEquals(COUNT-1,second.stream().filter(claim->claim.metadata()!=null).count());assertEquals(2,loads.get(),"Read snapshots must never be cached between calls");
        }
    }
    @Test void aFailedCatalogReadIsNotRepeatedForEveryClaimAndGrantsNothing()throws Exception{
        var loads=new AtomicInteger();try(var c=Database.getConnection()){
            var claims=OzonReviewedVariantProof.claims(c,shop,true,(connection,s)->{loads.incrementAndGet();throw new IllegalArgumentException("unverified_catalog_gtin");});
            assertEquals(1,loads.get());assertEquals(COUNT,claims.size());assertTrue(claims.stream().allMatch(claim->claim.metadata()==null));
        }
    }
    @Test void changingTheShopOwnerDuringTheReadCannotReuseTheImmutableCatalogSnapshot()throws Exception{
        try(var c=Database.getConnection()){
            assertThrows(SecurityException.class,()->OzonReviewedVariantProof.claims(c,shop,true,(connection,s)->{
                var snapshot=OzonReviewedVariantProof.readExactCatalog(connection,s);
                try(var update=Database.getConnection();var statement=update.createStatement()){statement.execute("UPDATE shops SET api_key='different-fixture' WHERE id=2");}
                return snapshot;
            }));
        }
    }
}
