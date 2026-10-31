package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.RegistrationReviewedValues;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbOzonQueuedGuardTest {
    @TempDir Path root;Shop wb,ozon;WbOzonLinkRepository repo;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);ozon=GtinWriteTestFixture.shop(Marketplace.OZON);repo=new WbOzonLinkRepository();}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private ProductSnapshot snapshot(String variant,String color){
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"100",variant);
        var one=new ProductVariant("200","model",color,"L","",false,"",List.of("00000000000017"),ProductVariant.GtinEvidence.UNVERIFIED);
        var two=new ProductVariant("201","model",color,"XL","",false,"",List.of("00000000000024"),ProductVariant.GtinEvidence.UNVERIFIED);
        return new ProductSnapshot(key,"revision","title","","brand","model","category",List.of(),List.of(),List.of(one,two),new PackageDimensions(null,null,null,null),List.of(),Instant.now());
    }
    private WbOzonLinkRepository.Link link(String variant,String product,String offer,String sku,String gtin,long good){
        return new WbOzonLinkRepository.Link(1,"100",variant,ListingIdentity.fingerprint(wb),2,product,offer,sku,ListingIdentity.fingerprint(ozon),gtin,good,RegisteredGtinProof.source(wb),RegisteredGtinProof.source(ozon),RegistrationReviewedValues.productRevision(snapshot(variant,"black")),"a".repeat(64),"EXACT_BARCODE","b".repeat(64),Instant.now());
    }
    private GtinSyncModels.ProductKey target(WbOzonLinkRepository.Link l){return new GtinSyncModels.ProductKey(2,Marketplace.OZON,l.ozonProductId(),l.ozonOfferId());}
    private String plan(List<WbOzonLinkRepository.Link> links){
        String id=UUID.randomUUID().toString();var rows=new ArrayList<CatalogSyncModels.Row>();
        for(var l:links){var details=new CatalogProductData(Map.of("@catalog_id",List.of(Long.toString(l.goodId())),"@catalog_status",List.of("published")),List.of(),List.of());
            var card=new GtinSyncModels.RegisteredGtin(l.gtin(),"model","black","L",true,true,l.catalogSource(),Instant.now(),details);
            rows.add(new CatalogSyncModels.Row(UUID.randomUUID().toString(),target(l),card,List.of(),List.of(),null,Map.of(),"","",CatalogSyncModels.State.READY,""));}
        new CatalogSyncRepository().insert(new CatalogSyncModels.Job(id,2,"OZON",ListingIdentity.fingerprint(ozon),Set.of(CatalogSyncModels.Field.GTIN),Instant.now(),false,false,rows));
        repo.confirmAndSaveDispatch(wb,ozon,links,id);return id;
    }
    @Test void rechecksPhysicalWbBeforeWriteAndSharesOneReadForSameArticle(){
        var one=link("200","501","offer-L","9001","00000000000017",91);var two=link("201","502","offer-XL","9002","00000000000024",92);
        repo.save(wb,ozon,List.of(one,two));plan(List.of(one,two));var requested=Map.of(target(one),one.gtin(),target(two),two.gtin());var reads=new AtomicInteger();
        assertDoesNotThrow(()->WbOzonGtinDispatchService.verifyQueuedCrossBatch(ozon,requested,(s,k)->{reads.incrementAndGet();return snapshot("200","black");}));
        assertEquals(1,reads.get(),"same WB article should use one fresh observation per guard");
        assertEquals("WB_OZON_WB_PRODUCT_CHANGED",assertThrows(IllegalStateException.class,()->WbOzonGtinDispatchService.verifyQueuedCrossBatch(ozon,requested,(s,k)->snapshot("200","red"))).getMessage());
        assertTrue(new GtinSyncRepository().verifiedMappings(ozon).isEmpty());
    }
}
