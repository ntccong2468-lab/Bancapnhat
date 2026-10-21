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
    @Test void observedModerationAndDraftsAreNotCountedOrFilteredAsCatalogErrors()throws Exception {
        var shop=new com.vncode.app.models.Shop(1,"Fixture WB","fixture");
        String receipt=com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        long existingErrors=new ZnackRegistrationQueryRepository(receipt,new com.vncode.app.features.sync.RegistrationCardIndex(shop)).page(criteria("",0)).summary().errors();
        var cards=new ArrayList<RegisteredGtin>();var codes=List.of("00000000000017","00000000000024","00000000000031");var states=List.of("errors","moderation","draft");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,?,?)")){
            for(int i=0;i<codes.size();i++){
                p.setLong(1,501+i);p.setString(2,codes.get(i));p.executeUpdate();
                var details=com.vncode.app.integration.znack.registration.CatalogProductDataParser.parse(com.google.gson.JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\""+states.get(i)+"\"]}").getAsJsonObject());
                cards.add(new RegisteredGtin(codes.get(i),"same-article","","XL",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),Instant.now(),details));
            }
        }
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,cards);
        var observed=new ZnackRegistrationQueryRepository(receipt,new com.vncode.app.features.sync.RegistrationCardIndex(shop));
        assertEquals(existingErrors+1,observed.page(criteria("",0)).summary().errors());assertEquals(2,observed.page(criteria("",0)).summary().waiting());
        var errors=observed.page(new SearchCriteria(1,"",List.of(),"ERROR",300,0)).rows();
        assertTrue(errors.stream().anyMatch(r->r.chrtId()==501));assertFalse(errors.stream().anyMatch(r->r.chrtId()==502||r.chrtId()==503));
        var processing=observed.page(new SearchCriteria(1,"",List.of(),"IN_PROGRESS",300,0)).rows();
        assertEquals(List.of(502L,503L),processing.stream().map(Sku::chrtId).toList(),
                "A draft already observed on Catalog must appear beside moderated cards");
        var draft=processing.stream().filter(row->row.chrtId()==503).findFirst().orElseThrow();
        assertFalse(draft.hasFeed(),"Showing a Catalog draft must not invent a registration receipt");
        assertTrue(observed.page(new SearchCriteria(1,"",List.of(),"NOT_CREATED",300,0)).rows()
                .stream().noneMatch(row->row.chrtId()==502||row.chrtId()==503));
        assertTrue(observed.allEligible(new SearchCriteria(1,"",List.of(),"IN_PROGRESS",300,0))
                .stream().noneMatch(row->row.chrtId()==503),"An existing draft is not a new submission");
    }
    @Test void aPureIdentifiedDraftCanBeSelectedForReviewButAnExistingFeedCannotBeReplayed()throws Exception {
        var shop=new com.vncode.app.models.Shop(1,"Fixture WB","fixture");String receipt=com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("UPDATE wb_product_sizes SET tech_size='XXL' WHERE shop_id=1 AND chrt_id<>503");
            statement.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,100,3,'Цвет','[\"Blue\"]')");
        }
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("901"),"@catalog_status",List.of("draft"),"@catalog_detailed_status",List.of("draft"),"@catalog_signed",List.of("false")),List.of(),List.of());
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","same-article","Blue","XL",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),Instant.now(),details)));
        var criteria=new SearchCriteria(1,"",List.of(),"IN_PROGRESS",300,0);
        var observed=new ZnackRegistrationQueryRepository(receipt,new com.vncode.app.features.sync.RegistrationCardIndex(shop));
        assertEquals(List.of(503L),observed.allSelectable(criteria).stream().map(Sku::chrtId).toList());
        assertEquals(List.of(503L),observed.allEligible(criteria).stream().map(Sku::chrtId).toList());
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,good_id,status,error_message,created_at,updated_at) VALUES(1,503,100,'00000000000017',901,'PROCESSING','CATALOG_CARD_DRAFT','fixture','fixture')");
        }
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,shop.getName())).getSettings();
        new com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository().bindScope(shop,503,com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(settings));
        observed=new ZnackRegistrationQueryRepository(receipt,new com.vncode.app.features.sync.RegistrationCardIndex(shop));
        assertEquals(List.of(503L),observed.allSelectable(criteria).stream().map(Sku::chrtId).toList());
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE znack_card_registrations SET feed_id='previous-submission' WHERE shop_id=1 AND chrt_id=503");}
        observed=new ZnackRegistrationQueryRepository(receipt,new com.vncode.app.features.sync.RegistrationCardIndex(shop));
        assertTrue(observed.allEligible(criteria).isEmpty(),"A known feed stays on readback rather than becoming another reviewed POST");
    }
    @Test void unsentLocalWorkStaysNotCreatedUntilCatalogAcceptsItsFeed()throws Exception {
        var shop=new com.vncode.app.models.Shop(1,"Fixture WB","fixture");
        String receipt=com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        var scope=com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(
                new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,"Fixture WB")).getSettings());
        var nativeRows=new com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository();
        var statuses=List.of(Status.CHECKING,Status.READY_TO_SUBMIT,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,
                Status.GTIN_GENERATED,Status.FEED_SUBMITTING,Status.FEED_REVIEW_REQUIRED,Status.FEED_SUBMITTED,
                Status.PROCESSING,Status.READY_TO_SIGN,Status.CHECKING,Status.ALLOCATING_GTIN);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,feed_id,status,created_at,updated_at) VALUES(1,?,100,?,?,?,'fixture','fixture')")){
            for(int i=0;i<statuses.size();i++){
                long variant=10+i;boolean allocated=!Set.of(10L,12L,21L).contains(variant);
                p.setLong(1,variant);p.setString(2,allocated?String.format(Locale.ROOT,"%014d",100_000+variant):null);
                p.setString(3,Set.of(17L,19L).contains(variant)?"accepted-feed-"+variant:"");p.setString(4,statuses.get(i).name());p.executeUpdate();
            }
        }
        for(long variant=10;variant<=21;variant++)nativeRows.bindScope(shop,variant,scope);
        var observed=new ZnackRegistrationQueryRepository(receipt,new com.vncode.app.features.sync.RegistrationCardIndex(shop));
        for(var query:List.of(repository,observed)){
            var notCreated=new SearchCriteria(1,"",List.of(),"NOT_CREATED",300,0);
            assertEquals(1_220,query.page(notCreated).filtered());
            var selected=query.allEligible(notCreated).stream().map(Sku::chrtId).toList();
            assertTrue(selected.containsAll(List.of(10L,11L,12L,13L,1_230L)),"Selection must include every safe unsent checkpoint, beyond the loaded page");
            assertFalse(selected.stream().anyMatch(id->id>=14&&id<=21),"An accepted or uncertain feed must not be submitted as a new card");
            var processing=query.page(new SearchCriteria(1,"",List.of(),"IN_PROGRESS",300,0));
            assertEquals(List.of(17L,19L),processing.rows().stream().map(Sku::chrtId).toList());
            assertEquals(1,processing.summary().waiting());assertEquals(1,processing.summary().readyToSign());
            var errors=query.page(new SearchCriteria(1,"",List.of(),"ERROR",300,0)).rows().stream().map(Sku::chrtId).toList();
            assertTrue(errors.containsAll(List.of(14L,15L,16L,20L)),"Legacy allocations and uncertain POST outcomes need a report/reconciliation, never another POST");
        }
    }
}
