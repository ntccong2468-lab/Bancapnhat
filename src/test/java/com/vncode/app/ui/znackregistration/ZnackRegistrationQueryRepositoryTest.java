package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.gtinsync.GtinSyncRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRegistrationQueryRepositoryTest {
    @TempDir Path temp;
    final ZnackRegistrationQueryRepository repository=new ZnackRegistrationQueryRepository();
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture WB','WILDBERRIES','fixture'),(2,'Other WB','WILDBERRIES','fixture'),(3,'Fixture Ozon','OZON','fixture')");
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,subject_name,brand,synced_at) VALUES(1,100,'same-article','Fixture','Category','Brand','fixture'),(2,100,'same-article','Foreign','Category','Brand','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(2,1,100,'XL')");
                s.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,100,1,'Пол','[\"Мужской\"]'),(1,100,2,'ТН ВЭД','[\"6103220000\"]')");
            }
            try(var p=c.prepareStatement("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,?,100,'XL')")) {
                for(int id=1;id<=1_230;id++){p.setInt(1,id);p.addBatch();}p.executeBatch();
            }
            try(var s=c.createStatement()) {
                s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,status,created_at,updated_at) VALUES(1,1,100,'04631993764370','PUBLISHED','fixture','revision-1')");
                s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,created_at,updated_at) VALUES(1,2,100,'GTIN_REVIEW_REQUIRED','fixture','revision-2')");
            }
            c.commit();
        }
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    SearchCriteria criteria(String query,int offset){return new SearchCriteria(1,query,List.of(),"ALL",10_000,offset);}

    @Test void changedGroupIsDetectedForBothLoadedRowsAndFrozenBackgroundSelection()throws Exception {
        try(var c=Database.getConnection();var st=c.createStatement()){st.execute("UPDATE znack_card_registrations SET tnved='6204',feed_id='old-feed' WHERE shop_id=1 AND chrt_id=1");}
        var query=new SearchCriteria(1,"",List.of(),"ALL",300,0);var page=repository.page(query);var old=page.rows().stream().filter(r->r.chrtId()==1).findFirst().orElseThrow();assertTrue(old.tnvedGroupChanged());assertTrue(page.details().get(1L).groupChanged());assertEquals("6103220000",page.details().get(1L).tnved());
        assertTrue(repository.allEligible(query).stream().anyMatch(r->r.chrtId()==1&&r.tnvedGroupChanged()));
    }
    @Test void stableBoundedPagesReachEveryVariantBeyondFiveHundred() {
        var seen=new LinkedHashSet<Long>();
        for(int offset=0;offset<1_230;offset+=ZnackRegistrationQueryRepository.PAGE_SIZE) {
            var page=repository.page(criteria("",offset));
            assertEquals(1_230,page.filtered());assertTrue(page.rows().size()<=300);
            for(var row:page.rows())assertTrue(seen.add(row.chrtId()),"Duplicate tied order");
            assertEquals(1_230,page.summary().total());assertEquals(1,page.summary().published());assertEquals(1,page.summary().errors());
        }
        assertEquals(1_230,seen.size());assertEquals(1L,seen.iterator().next());assertTrue(seen.contains(1_230L));
    }
    @Test void freshGtinCanBeSearchedBeforeItsBarcodeIsAddedToWb() {
        var page=repository.page(criteria("04631993764370",0));
        assertEquals(1,page.filtered());assertEquals(1,page.rows().size());assertEquals(1,page.rows().getFirst().chrtId());
        assertTrue(page.rows().getFirst().barcodes().isEmpty());
        assertEquals("Мужской",page.details().get(1L).gender());assertEquals("6103220000",page.details().get(1L).tnved());
    }
    @Test void allFilteredSelectionFreezesOwnedRevisionsBeyondLoadedRows() {
        var selected=repository.allEligible(criteria("",0));
        assertEquals(1_229,selected.size());assertTrue(selected.stream().noneMatch(row->row.status()==Status.PUBLISHED));
        assertEquals("revision-2",selected.stream().filter(row->row.chrtId()==2).findFirst().orElseThrow().registrationRevision());
        assertThrows(UnsupportedOperationException.class,()->selected.clear());
        assertTrue(repository.allEligible(new SearchCriteria(1,"",List.of("Missing category"),"ALL",300,0)).isEmpty());
    }
    @Test void catalogCountsIncludeUnlinkedCardsAndDoNotTreatUnpublishedAsErrors() {
        Instant at=Instant.parse("2026-10-13T00:00:00Z");
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(
                new RegisteredGtin("04631993764370","same-article","","XL",true,true,"fixture",at),
                new RegisteredGtin("04631993764387","unlinked","","XL",true,false,"fixture",at)));
        var summary=repository.page(criteria("",0)).summary();
        assertEquals(2,summary.catalog());assertEquals(1,summary.catalogPublished());assertEquals(1,summary.catalogUnlinked());
        assertEquals(1,summary.errors());assertEquals(at,summary.syncedAt());
    }
    @Test void rejectsMissingOrWrongMarketplaceOwnerAndNeverLeaksOtherShop() {
        assertThrows(IllegalArgumentException.class,()->repository.page(new SearchCriteria(3,"",List.of(),"ALL",300,0)));
        assertThrows(IllegalArgumentException.class,()->repository.allEligible(new SearchCriteria(99,"",List.of(),"ALL",300,0)));
        var other=repository.page(new SearchCriteria(2,"",List.of(),"ALL",300,0));
        assertEquals(1,other.filtered());assertEquals("Foreign",other.rows().getFirst().title());assertTrue(other.details().get(1L).gender().isBlank());
    }
}
