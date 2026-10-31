package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

class WbOzonGtinReviewServiceTest {
    @TempDir Path root;Shop wb,ozon;Stub stub;WbOzonGtinReviewService service;
    static final String A="00000000000017",B="00000000000024";
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        stub=new Stub();service=new WbOzonGtinReviewService(stub,new WbOzonLinkRepository());
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void scanAndApproveUseFreshOwnerBoundObservationsAndSaveExactVariants()throws Exception{
        var scan=service.scan(wb,ozon,s->{});assertTrue(scan.warnings().isEmpty());assertEquals(2,scan.rows().size());
        var chosen=scan.rows().stream().map(r->r.candidates().getFirst()).toList();
        var report=service.approveWithReport(wb,ozon,scan,chosen,s->{});
        assertTrue(report.failures().isEmpty());assertEquals(2,report.links().size());
        assertEquals(2,new WbOzonLinkRepository().list(wb,ozon).size());assertEquals(2,stub.wbReads);assertEquals(2,stub.ozonReads);
        assertNotEquals(report.links().get(0).wbCatalogSource(),report.links().get(0).catalogSource());
        var next=service.scanCached(wb,ozon,s->{});assertEquals(2,next.savedLinks().size());assertTrue(next.savedLinks().stream().allMatch(x->!x.needsReview()&&x.current()!=null));
    }
    @Test void oneChangedVariantDoesNotBlockAnotherAndIsNeverSaved()throws Exception{
        var scan=service.scan(wb,ozon,s->{});var chosen=scan.rows().stream().map(r->r.candidates().getFirst()).toList();
        stub.changed=true;var report=service.approveWithReport(wb,ozon,scan,chosen,s->{});
        assertEquals(1,report.links().size());assertEquals(1,report.failures().size());assertEquals("WB_PRODUCT_CHANGED",report.failures().getFirst().reason());
        assertEquals(1,new WbOzonLinkRepository().list(wb,ozon).size());
    }
    @Test void partialScanCannotAutoApproveButExplicitManualPairStillRequiresFreshProof()throws Exception{
        stub.partial=true;var scan=service.scan(wb,ozon,s->{});assertFalse(scan.warnings().isEmpty());
        var first=scan.rows().getFirst();assertFalse(first.candidates().getFirst().autoEligible());
        var denied=service.approveWithReport(wb,ozon,scan,List.of(first.candidates().getFirst()),s->{});
        assertEquals("WB_OZON_MANUAL_REVIEW_REQUIRED",denied.failures().getFirst().reason());assertTrue(denied.links().isEmpty());
        var manual=service.candidatesFor(scan,first.ozon(),first.candidates().getFirst().wb().key().cardId());
        var approved=service.approveWithReport(wb,ozon,scan,manual,s->{});
        assertEquals(1,approved.links().size());assertTrue(approved.failures().isEmpty());
    }
    @Test void changedOwnerBlocksBeforeAnyReadOrSave()throws Exception{
        var scan=service.scan(wb,ozon,s->{});var chosen=scan.rows().stream().map(r->r.candidates().getFirst()).toList();
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='changed' WHERE id=?")){p.setInt(1,ozon.getId());p.executeUpdate();}
        assertThrows(SecurityException.class,()->service.approveWithReport(wb,ozon,scan,chosen,s->{}));assertEquals(0,stub.wbReads);
    }
    @Test void wbPublishedJournalCanSupplyGtinBeforeItIsOnWbBarcode()throws Exception{
        stub.withoutBarcode=true;String now=Instant.now().toString();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,good_id,status,created_at,updated_at) VALUES(1,201,101,?,91,'PUBLISHED',?,?)")){p.setString(1,A);p.setString(2,now);p.setString(3,now);p.executeUpdate();}
        var proof=RegisteredGtinProof.current(wb);
        new ZnackCardRegistrationRepository().bindScope(wb,201,new ZnackRegistrationScope(proof.inn(),proof.environment(),proof.fingerprint()));
        var scan=service.scan(wb,ozon,s->{});var first=scan.rows().getFirst();assertEquals(1,first.candidates().size());
        assertNotNull(first.candidates().getFirst().nativeAssociation());
        var result=service.approveWithReport(wb,ozon,scan,List.of(first.candidates().getFirst()),s->{});
        assertEquals(1,result.links().size());assertTrue(result.failures().isEmpty());
        assertTrue(WbOzonSourceEvidence.matches(wb,stub.wa,A,91));assertFalse(WbOzonSourceEvidence.matches(wb,stub.wa,A,92));
    }
    @Test void rateLimitStopsRemainingReadsAndReportsUnprocessed()throws Exception{
        var scan=service.scan(wb,ozon,s->{});var chosen=scan.rows().stream().map(r->r.candidates().getFirst()).toList();stub.limited=true;
        var result=service.approveWithReport(wb,ozon,scan,chosen,s->{});
        assertEquals(2,result.failures().size());assertTrue(result.links().isEmpty());assertEquals(1,stub.wbReads);
        assertEquals("API_COOLDOWN",result.failures().getLast().reason());
    }
    @Test void scanReadsEveryPageAndRejectsRepeatedCursorOrIncompleteList()throws Exception{
        stub.paged=true;var scan=service.scan(wb,ozon,s->{});assertEquals(2,scan.wbRows().size());assertEquals(2,scan.rows().size());
        stub.cyclic=true;assertThrows(java.io.IOException.class,()->service.scan(wb,ozon,s->{}));
        stub.cyclic=false;stub.truncated=true;assertThrows(java.io.IOException.class,()->service.scan(wb,ozon,s->{}));
    }
    private final class Stub implements WbOzonGtinReviewService.Gateway {
        boolean changed,partial,withoutBarcode,limited,paged,cyclic,truncated;int wbReads,ozonReads;
        final ProductKey wa=new ProductKey(1,Marketplace.WILDBERRIES,"101","201"),wbkey=new ProductKey(1,Marketplace.WILDBERRIES,"102","202");
        final ProductKey oa=new ProductKey(2,Marketplace.OZON,"501","offer-a"),ob=new ProductKey(2,Marketplace.OZON,"502","offer-b");
        ProductRow row(ProductKey key,String article,String code){return new ProductRow(key,"title",""+article,"","costume","Brand","Blue","52",withoutBarcode&&key.equals(wa)?List.of():List.of(code),"","","Cotton","6112110000",DocumentState.VALID,"Male",true,"52","52");}
        RegisteredGtin card(String article,String code,String id){return new RegisteredGtin(code,article,"Blue","52",true,true,RegisteredGtinProof.source(ozon),Instant.now(),new CatalogProductData(Map.of("@catalog_id",List.of(id),"@catalog_status",List.of("published"),"tnved",List.of("6112110000"),"brand",List.of("Brand"),"gender",List.of("Male"),"material",List.of("Cotton")),List.of(),List.of()));}
        public ProductRefreshService.Report refresh(Shop s,Consumer<ProductRefreshService.Progress> p){p.accept(new ProductRefreshService.Progress(2,2,partial?1:0));return new ProductRefreshService.Report(2,partial?1:2,partial?1:0);}
        public ProductPage page(ProductQuery q){
            var rows=q.marketplace()==Marketplace.WILDBERRIES?List.of(row(wa,"ArticleA",A),row(wbkey,"ArticleB",B)):List.of(row(oa,"ArticleA",A),row(ob,"ArticleB",B));
            if(!paged)return new ProductPage(rows,2,null);
            if(q.cursor()==null)return new ProductPage(List.of(rows.getFirst()),2,truncated?null:"next");
            return new ProductPage(List.of(rows.getLast()),2,cyclic?"next":null);
        }
        public List<ProductKey> keys(ProductQuery q){return q.marketplace()==Marketplace.WILDBERRIES?List.of(wa,wbkey):List.of(oa,ob);}
        public List<RegisteredGtin> catalog(Shop s,Consumer<NationalCatalogGtinSource.CardIssue> issues){return List.of(card("ArticleA",A,"91"),card("ArticleB",B,"92"));}
        public ProductSnapshot wb(Shop s,ProductKey key){
            wbReads++;boolean first=key.equals(wa);String article=first?"ArticleA":"ArticleB",gtin=first?A:B;
            if(limited)throw new RuntimeException(new com.vncode.app.integration.listing.ListingRateLimitException(new okhttp3.Headers.Builder().add("Retry-After","1").build()));
            var attributes=List.of(new ProductAttribute("1","ТН ВЭД",List.of("6112110000"),""),new ProductAttribute("2","Состав",List.of("Cotton"),""),new ProductAttribute("3","Пол",List.of("Male"),""));
            var variant=new ProductVariant(key.variantId(),article,"Blue","52","",false,"52",withoutBarcode&&first?List.of():List.of(gtin),ProductVariant.GtinEvidence.UNVERIFIED);
            return new ProductSnapshot(key,"live:test",changed&&first?"changed":"title","","Brand",article,"1",List.of(),attributes,List.of(variant),null,List.of(),Instant.now());
        }
        public OzonExistingCardLinkService.Review ozon(Shop s,GtinSyncModels.ProductKey key,String gtin){
            ozonReads++;boolean first=key.productId().equals("501");String article=first?"ArticleA":"ArticleB";
            return new OzonExistingCardLinkService.Review(key,gtin,first?91:92,first?"901":"902","title",article,"Blue","52",article,"Blue","52","6112110000","x","y","a".repeat(64),"b".repeat(64));
        }
    }
}
