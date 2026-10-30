package com.vncode.app.ui.znackregistration;

import static org.junit.jupiter.api.Assertions.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinSyncModels;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OzonGtinConflictReviewTest {
    @TempDir Path dir;
    Shop shop;SyncRunRepository reports;String run;SyncRunRepository.Item row;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",dir.toString());Database.initDatabase();
        shop=new Shop(1,"Synthetic",Marketplace.OZON,"fixture","secret");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','fixture','secret')");}
        reports=new SyncRunRepository();run=reports.begin(shop,"znack_marketplace","READBACK");
        reports.add(shop,run,"101","offer-101","sku-101","Article","Blue","M","04631993764370","NEEDS_ACTION","GTIN_ON_ANOTHER_PRODUCT","{}");
        row=reports.items(run,0,10).getFirst();
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private OzonGtinConflictReview.Probe probe(boolean own,boolean other){return new OzonGtinConflictReview.Probe(){
        public boolean alreadyOnSelected(GtinSyncModels.ProductKey key,String gtin){assertEquals("101",key.productId());assertEquals("offer-101",key.variantId());return own;}
        public boolean belongsToAnother(GtinSyncModels.ProductKey key,String gtin){return other;}
    };}
    @Test void onlyCurrentConfirmedRemoteConflictOpensExactSelectedProduct()throws Exception {
        var report=reports.find(run).orElseThrow();assertTrue(OzonGtinConflictReview.candidate(shop,report,row));
        var selected=new OzonGtinConflictReview(probe(false,true)).verify(shop,report,row);var key=selected.key();
        assertEquals(1,key.shopId());assertEquals("101",key.cardId());assertEquals("offer-101",key.variantId());assertEquals(row.gtin(),selected.gtin());
        assertEquals("GTIN_ON_ANOTHER_PRODUCT",reports.items(run,0,10).getFirst().error(),"Read-only review must not change the old GTIN or submit a card");
    }
    @Test void refusesWhenOzonNoLongerShowsConflictOrSelectedProductAlreadyContainsGtin() {
        var report=reports.find(run).orElseThrow();
        assertThrows(IllegalStateException.class,()->new OzonGtinConflictReview(probe(false,false)).verify(shop,report,row));
        assertThrows(IllegalStateException.class,()->new OzonGtinConflictReview(probe(true,true)).verify(shop,report,row));
    }
    @Test void unknownResultAndStaleReportNeverAllowNewCard()throws Exception {
        var report=reports.find(run).orElseThrow();
        var unavailable=new OzonGtinConflictReview(new OzonGtinConflictReview.Probe(){
            public boolean alreadyOnSelected(GtinSyncModels.ProductKey key,String gtin){return false;}
            public boolean belongsToAnother(GtinSyncModels.ProductKey key,String gtin)throws IOException{throw new IOException("NETWORK_TIMEOUT");}
        });
        assertThrows(IOException.class,()->unavailable.verify(shop,report,row));
        reports.update(shop,row,"WAITING_REMOTE","READBACK","GTIN_READBACK_PENDING",null);
        assertThrows(IllegalStateException.class,()->new OzonGtinConflictReview(probe(false,true)).verify(shop,report,row));
    }
    @Test void localBatchDuplicatesAndOtherMarketsAreNotPresentedAsConfirmedRemoteConflicts()throws Exception {
        reports.update(shop,row,"NEEDS_ACTION","VALIDATING","GTIN_SELECTED_MULTIPLE_PRODUCTS",null);
        var local=reports.items(run,0,10).getFirst();
        assertFalse(OzonGtinConflictReview.candidate(shop,reports.find(run).orElseThrow(),local));
        assertFalse(OzonGtinConflictReview.candidate(new Shop(1,"Other",Marketplace.WILDBERRIES,"fixture","secret"),reports.find(run).orElseThrow(),row));
    }
    @Test void changedOwnerIsRejectedEvenIfLocalIdMatches()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id=1");}
        assertThrows(SecurityException.class,()->new OzonGtinConflictReview(probe(false,true)).verify(shop,reports.find(run).orElseThrow(),row));
    }
    @Test void missingOrUnknownChildPlanCannotAuthorizeASecondRegistration() {
        reports.update(shop,row,"NEEDS_ACTION","READBACK","GTIN_ON_ANOTHER_PRODUCT","unknown-parent-plan");
        var changed=reports.items(run,0,10).getFirst();
        assertThrows(RuntimeException.class,()->new OzonGtinConflictReview(probe(false,true)).verify(shop,reports.find(run).orElseThrow(),changed));
    }
}
