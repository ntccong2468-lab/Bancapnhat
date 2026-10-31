package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.catalogsync.CatalogSyncModels;
import com.vncode.app.features.catalogsync.CatalogSyncRepository;
import com.vncode.app.features.catalogsync.CatalogSyncService;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite plan boundary, without a marketplace server or any seller credentials. */
class WbOzonCatalogBoundaryTest {
    @TempDir Path root;
    Shop wb,ozon;
    WbOzonLinkRepository links;
    GtinSyncRepository gtins;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());
        Database.initDatabase();
        wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        links=new WbOzonLinkRepository();
        gtins=new GtinSyncRepository();
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    private WbOzonLinkRepository.Link link(){
        return new WbOzonLinkRepository.Link(wb.getId(),"100","200",ListingIdentity.fingerprint(wb),
                ozon.getId(),"501","offer-XL","9001",ListingIdentity.fingerprint(ozon),GtinWriteTestFixture.GTIN,91,
                RegisteredGtinProof.source(wb),RegisteredGtinProof.source(ozon),"a".repeat(64),"b".repeat(64),"MANUAL_PAIR","c".repeat(64),Instant.now());
    }
    private String confirmedPlan(WbOzonLinkRepository.Link link,boolean alreadyOnOzon){
        links.save(wb,ozon,List.of(link));
        var key=new GtinSyncModels.ProductKey(ozon.getId(),Marketplace.OZON,link.ozonProductId(),link.ozonOfferId());
        var card=new GtinSyncModels.RegisteredGtin(link.gtin(),"Fixture","","",true,true,RegisteredGtinProof.source(ozon),Instant.now(),
                new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of()));
        var row=new CatalogSyncModels.Row(UUID.randomUUID().toString(),key,card,alreadyOnOzon?List.of(link.gtin()):List.of(),List.of(),null,Map.of(),"","",CatalogSyncModels.State.READY,"");
        String id=UUID.randomUUID().toString();
        new CatalogSyncRepository().insert(new CatalogSyncModels.Job(id,ozon.getId(),"OZON",ListingIdentity.fingerprint(ozon),Set.of(CatalogSyncModels.Field.GTIN),Instant.now(),false,false,List.of(row)));
        links.confirmAndSaveDispatch(wb,ozon,List.of(link),id);
        return id;
    }

    @Test void genericHistoryCannotRunAConfirmedCrossMarketplacePlan() throws Exception {
        String id=confirmedPlan(link(),false);
        var failure=assertThrows(IllegalStateException.class,()->new CatalogSyncService().run(ozon,id,false));
        assertEquals("WB_OZON_RESUME_FROM_LINK_WORKFLOW",failure.getMessage());
        assertEquals(CatalogSyncModels.State.READY,new CatalogSyncRepository().read(ozon,id).rows().getFirst().state());
        assertTrue(gtins.verifiedMappings(ozon).isEmpty());
    }

    @Test void changedWbCredentialsCannotMarkAlreadyPresentOzonBarcodeVerified() throws Exception {
        String id=confirmedPlan(link(),true);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=?")){
            p.setString(1,"rotated-fixture");p.setInt(2,wb.getId());assertEquals(1,p.executeUpdate());
        }
        var changed=new Shop(wb.getId(),wb.getName(),wb.getMarketplace(),wb.getClientId(),"rotated-fixture");
        assertThrows(SecurityException.class,()->new CatalogSyncService().runReviewedWbOzon(changed,ozon,id));
        assertEquals(CatalogSyncModels.State.READY,new CatalogSyncRepository().read(ozon,id).rows().getFirst().state());
        assertTrue(gtins.verifiedMappings(ozon).isEmpty());
    }
}
