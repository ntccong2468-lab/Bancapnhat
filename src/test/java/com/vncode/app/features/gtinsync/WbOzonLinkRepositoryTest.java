package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
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

class WbOzonLinkRepositoryTest {
    @TempDir Path root;
    Shop wb, ozon;
    WbOzonLinkRepository repository;

    @BeforeEach void init() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString()); Database.initDatabase();
        wb = GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        ozon = GtinWriteTestFixture.shop(Marketplace.OZON);
        repository = new WbOzonLinkRepository();
    }
    @AfterEach void clear() { System.clearProperty("vncode.appdata.dir"); }

    private WbOzonLinkRepository.Link link(String product, String offer, String sku, String gtin) {
        return new WbOzonLinkRepository.Link(wb.getId(),"100","200",ListingIdentity.fingerprint(wb),
                ozon.getId(),product,offer,sku,ListingIdentity.fingerprint(ozon),gtin,91,
                RegisteredGtinProof.source(wb),RegisteredGtinProof.source(ozon),
                "a".repeat(64),"b".repeat(64),"MANUAL_PAIR","c".repeat(64),Instant.now());
    }

    @Test void persistsReviewAcrossRepositoryInstancesWithoutClaimingAnOzonWrite() {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        repository.save(wb,ozon,List.of(link));
        var reopened=new WbOzonLinkRepository();
        assertEquals(link,reopened.find(wb,ozon,"501","offer-XL").orElseThrow());
        assertEquals(List.of(link),reopened.list(wb,ozon));
        assertTrue(new GtinSyncRepository().verifiedMappings(ozon).isEmpty());
    }

    @Test void duplicateTargetOrGtinCannotBeSilentlyAssignedToAnotherOzonVariant() {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        repository.save(wb,ozon,List.of(link));
        var other=link("502","other","9002",GtinWriteTestFixture.GTIN);
        assertEquals("WB_OZON_GTIN_ALREADY_LINKED",assertThrows(IllegalStateException.class,()->repository.save(wb,ozon,List.of(other))).getMessage());
        assertTrue(repository.find(wb,ozon,"502","other").isEmpty());
        var differentSource=new WbOzonLinkRepository.Link(link.wbShopId(),"101",link.wbChrtId(),link.wbOwner(),link.ozonShopId(),link.ozonProductId(),link.ozonOfferId(),link.ozonSku(),link.ozonOwner(),link.gtin(),link.goodId(),link.wbCatalogSource(),link.catalogSource(),link.wbRevision(),link.ozonRevision(),link.evidenceKind(),link.evidenceDigest(),link.approvedAt());
        assertEquals("WB_OZON_LINK_REVIEW_CONFLICT",assertThrows(IllegalStateException.class,()->repository.save(wb,ozon,List.of(differentSource))).getMessage());
        assertEquals(link,repository.find(wb,ozon,"501","offer-XL").orElseThrow());
    }

    @Test void aBatchRejectsDuplicateGtinAtomically() {
        var one=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        var two=link("502","offer-L","9002",GtinWriteTestFixture.GTIN);
        assertThrows(IllegalArgumentException.class,()->repository.save(wb,ozon,List.of(one,two)));
        assertTrue(repository.list(wb,ozon).isEmpty());
    }

    @Test void changedTokenOrCatalogCertificateCannotReuseOldReview() throws Exception {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        repository.save(wb,ozon,List.of(link));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='rotated-fixture' WHERE id=?")){p.setInt(1,ozon.getId());p.executeUpdate();}
        assertThrows(SecurityException.class,()->repository.find(wb,ozon,"501","offer-XL"));
        var rotated=new Shop(ozon.getId(),ozon.getName(),ozon.getMarketplace(),ozon.getClientId(),"rotated-fixture");
        assertThrows(SecurityException.class,()->repository.find(wb,rotated,"501","offer-XL"));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM wb_ozon_gtin_links");var r=p.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1));}
    }

    @Test void credentialRotationAllowsOnlyACompleteFreshReviewWithOpaqueCompareAndSwap() throws Exception {
        var old=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        repository.save(wb,ozon,List.of(old));
        String staleToken=repository.reviewToken(wb,ozon,"501","offer-XL").orElseThrow();
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='rotated-fixture' WHERE id=?")){p.setInt(1,ozon.getId());p.executeUpdate();}
        var rotated=new Shop(ozon.getId(),ozon.getName(),ozon.getMarketplace(),ozon.getClientId(),"rotated-fixture");
        String opaque=repository.reviewToken(wb,rotated,"501","offer-XL").orElseThrow();
        assertEquals(staleToken,opaque);
        var reviewed=new WbOzonLinkRepository.Link(wb.getId(),"100","200",ListingIdentity.fingerprint(wb),rotated.getId(),"501","offer-XL","9001",ListingIdentity.fingerprint(rotated),GtinWriteTestFixture.GTIN,91,RegisteredGtinProof.source(wb),RegisteredGtinProof.source(rotated),"d".repeat(64),"e".repeat(64),"MANUAL_PAIR","f".repeat(64),Instant.now());
        repository.replaceReviewed(wb,rotated,opaque,reviewed);
        assertEquals(reviewed,repository.find(wb,rotated,"501","offer-XL").orElseThrow());
        assertEquals("WB_OZON_LINK_REVIEW_CHANGED",assertThrows(IllegalStateException.class,()->repository.replaceReviewed(wb,rotated,opaque,reviewed)).getMessage());
    }

    @Test void staleRowsDoNotHideFreshLinksOrExposeOldWbAndGtinData() throws Exception {
        var old=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        var oldOther=link("502","offer-L","9002","00000000000024");repository.save(wb,ozon,List.of(old,oldOther));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='rotated-fixture' WHERE id=?")){p.setInt(1,ozon.getId());p.executeUpdate();}
        var rotated=new Shop(ozon.getId(),ozon.getName(),ozon.getMarketplace(),ozon.getClientId(),"rotated-fixture");
        String version=repository.reviewToken(wb,rotated,"501","offer-XL").orElseThrow();
        var reviewed=new WbOzonLinkRepository.Link(wb.getId(),"100","200",ListingIdentity.fingerprint(wb),rotated.getId(),"501","offer-XL","9001",ListingIdentity.fingerprint(rotated),GtinWriteTestFixture.GTIN,91,RegisteredGtinProof.source(wb),RegisteredGtinProof.source(rotated),"d".repeat(64),"e".repeat(64),"MANUAL_PAIR","f".repeat(64),Instant.now());
        repository.replaceReviewed(wb,rotated,version,reviewed);
        var statuses=repository.listReviewStatus(wb,rotated);assertEquals(2,statuses.size());
        assertEquals(reviewed,statuses.getFirst().current());assertFalse(statuses.getFirst().needsReview());
        assertTrue(statuses.getLast().needsReview());assertNull(statuses.getLast().current());
        assertEquals("502",statuses.getLast().ozonProductId());assertEquals("offer-L",statuses.getLast().ozonOfferId());
    }

    @Test void replacementRequiresCurrentExpectedLinkAndRollsBackOnConflict() {
        var before=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        repository.save(wb,ozon,List.of(before));
        var after=new WbOzonLinkRepository.Link(before.wbShopId(),"101","201",before.wbOwner(),before.ozonShopId(),before.ozonProductId(),before.ozonOfferId(),before.ozonSku(),before.ozonOwner(),before.gtin(),92,before.wbCatalogSource(),before.catalogSource(),"d".repeat(64),"e".repeat(64),"MANUAL_PAIR","f".repeat(64),Instant.now());
        repository.replaceReviewed(wb,ozon,before,after);
        assertEquals(after,repository.find(wb,ozon,"501","offer-XL").orElseThrow());
        assertEquals("WB_OZON_LINK_REVIEW_CHANGED",assertThrows(IllegalStateException.class,()->repository.replaceReviewed(wb,ozon,before,after)).getMessage());
    }

    @Test void twoShopsWithDifferentCertificateButSameInnCanUseTheirOwnScopedSources() {
        GtinWriteTestFixture.saveSettings(ozon,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        assertNotEquals(link.wbCatalogSource(),link.catalogSource());
        repository.save(wb,ozon,List.of(link));
        assertEquals(link,repository.find(wb,ozon,"501","offer-XL").orElseThrow());
    }

    private String plan(WbOzonLinkRepository.Link link, boolean confirm) {
        var plans=new com.vncode.app.features.catalogsync.CatalogSyncRepository();String id=UUID.randomUUID().toString();
        var key=new GtinSyncModels.ProductKey(ozon.getId(),Marketplace.OZON,link.ozonProductId(),link.ozonOfferId());
        GtinSyncModels.RegisteredGtin card;
        try{card=new GtinSyncRepository().catalogItem(ozon.getId(),Marketplace.OZON,link.gtin());}
        catch(IllegalArgumentException absent){card=new GtinSyncModels.RegisteredGtin(link.gtin(),"Fixture","","",true,true,RegisteredGtinProof.source(ozon),Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of()));}
        var row=new com.vncode.app.features.catalogsync.CatalogSyncModels.Row(UUID.randomUUID().toString(),key,card,List.of(),List.of(),null,Map.of(),"","",com.vncode.app.features.catalogsync.CatalogSyncModels.State.READY,"");
        plans.insert(new com.vncode.app.features.catalogsync.CatalogSyncModels.Job(id,ozon.getId(),"OZON",ListingIdentity.fingerprint(ozon),Set.of(com.vncode.app.features.catalogsync.CatalogSyncModels.Field.GTIN),Instant.now(),false,false,List.of(row)));
        if(confirm)plans.confirm(ozon,id);return id;
    }

    @Test void confirmedDispatchSurvivesRestartAndPreventsAnotherPlanOrLinkChange() {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);repository.save(wb,ozon,List.of(link));
        String job=plan(link,false);repository.confirmAndSaveDispatch(wb,ozon,List.of(link),job);
        assertEquals(job,new WbOzonLinkRepository().findDispatch(wb,ozon,link).orElseThrow());
        assertEquals(List.of(link),new WbOzonLinkRepository().linksForDispatch(wb,ozon,job));
        assertDoesNotThrow(()->repository.confirmAndSaveDispatch(wb,ozon,List.of(link),job));
        String other=plan(link,false);
        assertEquals("WB_OZON_DISPATCH_RECONCILE_REQUIRED",assertThrows(IllegalStateException.class,()->repository.confirmAndSaveDispatch(wb,ozon,List.of(link),other)).getMessage());
        var changed=new WbOzonLinkRepository.Link(link.wbShopId(),link.wbNmId(),link.wbChrtId(),link.wbOwner(),link.ozonShopId(),link.ozonProductId(),link.ozonOfferId(),link.ozonSku(),link.ozonOwner(),link.gtin(),link.goodId(),link.wbCatalogSource(),link.catalogSource(),"d".repeat(64),link.ozonRevision(),link.evidenceKind(),link.evidenceDigest(),Instant.now());
        assertEquals("WB_OZON_DISPATCH_RECONCILE_REQUIRED",assertThrows(IllegalStateException.class,()->repository.save(wb,ozon,List.of(changed))).getMessage());
    }

    @Test void dispatchCannotBindOrphanOrDifferentTargetPlan() {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);repository.save(wb,ozon,List.of(link));
        String orphan=plan(link,true);
        assertEquals("WB_OZON_DISPATCH_RECONCILE_REQUIRED",assertThrows(IllegalStateException.class,()->repository.confirmAndSaveDispatch(wb,ozon,List.of(link),orphan)).getMessage());
        var different=link("502","offer-L","9002",GtinWriteTestFixture.GTIN);String wrong=plan(different,false);
        assertThrows(SecurityException.class,()->repository.confirmAndSaveDispatch(wb,ozon,List.of(link),wrong));
        assertTrue(repository.findDispatch(wb,ozon,link).isEmpty());
    }

    @Test void aPlanWithMatchingGtinButDifferentCatalogGoodIdCannotBeConfirmed() {
        var base=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);
        var wrong=new WbOzonLinkRepository.Link(base.wbShopId(),base.wbNmId(),base.wbChrtId(),base.wbOwner(),base.ozonShopId(),base.ozonProductId(),base.ozonOfferId(),base.ozonSku(),base.ozonOwner(),base.gtin(),92,base.wbCatalogSource(),base.catalogSource(),base.wbRevision(),base.ozonRevision(),base.evidenceKind(),base.evidenceDigest(),base.approvedAt());
        repository.save(wb,ozon,List.of(wrong));String job=plan(wrong,false);
        assertThrows(SecurityException.class,()->repository.confirmAndSaveDispatch(wb,ozon,List.of(wrong),job));
        assertFalse(new com.vncode.app.features.catalogsync.CatalogSyncRepository().read(ozon,job).confirmed());
    }

    @Test void retryOnlyAfterExplicitReviewAndTerminalUnsentOrVerifiedPlan() {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);repository.save(wb,ozon,List.of(link));
        String job=plan(link,false);repository.confirmAndSaveDispatch(wb,ozon,List.of(link),job);
        assertThrows(SecurityException.class,()->repository.clearTerminalDispatch(wb,ozon,List.of(link),job,false));
        assertEquals("WB_OZON_DISPATCH_RECONCILE_REQUIRED",assertThrows(IllegalStateException.class,()->repository.clearTerminalDispatch(wb,ozon,List.of(link),job,true)).getMessage());
        var plans=new com.vncode.app.features.catalogsync.CatalogSyncRepository();var row=plans.read(ozon,job).rows().getFirst();
        plans.update(ozon,job,row.next(com.vncode.app.features.catalogsync.CatalogSyncModels.State.BLOCKED,"possibly-sent","READBACK_REQUIRED"));
        assertEquals("WB_OZON_DISPATCH_RECONCILE_REQUIRED",assertThrows(IllegalStateException.class,()->repository.clearTerminalDispatch(wb,ozon,List.of(link),job,true)).getMessage());
        plans.update(ozon,job,row.next(com.vncode.app.features.catalogsync.CatalogSyncModels.State.BLOCKED,"","GTIN_VALIDATION_BLOCKED"));
        repository.clearTerminalDispatch(wb,ozon,List.of(link),job,true);
        assertTrue(repository.findDispatch(wb,ozon,link).isEmpty());
    }

    @Test void atomicConfirmationAndPointersSurviveRestartAndRejectOrphanConfirmedPlan() {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);repository.save(wb,ozon,List.of(link));
        String job=plan(link,false);assertTrue(repository.hasLinksForPlan(ozon,job));
        var key=new GtinSyncModels.ProductKey(ozon.getId(),Marketplace.OZON,"501","offer-XL");
        assertEquals("WB_OZON_DISPATCH_RECONCILE_REQUIRED",assertThrows(IllegalStateException.class,()->repository.findCrossTarget(ozon,key,link.gtin())).getMessage());
        repository.confirmAndSaveDispatch(wb,ozon,List.of(link),job);
        assertTrue(new com.vncode.app.features.catalogsync.CatalogSyncRepository().read(ozon,job).confirmed());
        assertEquals(job,new WbOzonLinkRepository().findDispatch(wb,ozon,link).orElseThrow());
        assertEquals(link,repository.findCrossTarget(ozon,key,link.gtin()).orElseThrow());
        assertEquals(Map.of(key,link),repository.findCrossTargets(ozon,Map.of(key,link.gtin())));
        assertEquals(List.of(link),repository.linksForDispatch(wb,ozon,job));
        assertDoesNotThrow(()->repository.confirmAndSaveDispatch(wb,ozon,List.of(link),job));
        var second=link("502","offer-L","9002","00000000000024");repository.save(wb,ozon,List.of(second));
        String orphan=plan(second,true);
        assertEquals("WB_OZON_DISPATCH_RECONCILE_REQUIRED",assertThrows(IllegalStateException.class,()->repository.confirmAndSaveDispatch(wb,ozon,List.of(second),orphan)).getMessage());
        assertTrue(repository.findDispatch(wb,ozon,second).isEmpty());
    }

    @Test void pointerFailureRollsBackConfirmation() throws Exception {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);repository.save(wb,ozon,List.of(link));
        String job=plan(link,false);
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("CREATE TRIGGER simulate_pointer_failure BEFORE INSERT ON wb_ozon_gtin_dispatch BEGIN SELECT RAISE(ABORT,'injected'); END");
        }
        assertThrows(IllegalStateException.class,()->repository.confirmAndSaveDispatch(wb,ozon,List.of(link),job));
        assertFalse(new com.vncode.app.features.catalogsync.CatalogSyncRepository().read(ozon,job).confirmed());
        assertTrue(repository.findDispatch(wb,ozon,link).isEmpty());
    }

    @Test void genericCatalogPlanCannotBypassCrossWorkflowBeforeOrAfterDispatchPointer() throws Exception {
        var link=link("501","offer-XL","9001",GtinWriteTestFixture.GTIN);repository.save(wb,ozon,List.of(link));
        String job=plan(link,false);var catalog=new com.vncode.app.features.catalogsync.CatalogSyncService();
        assertEquals("WB_OZON_RESUME_FROM_LINK_WORKFLOW",assertThrows(IllegalStateException.class,()->catalog.run(ozon,job,true)).getMessage());
        assertFalse(new com.vncode.app.features.catalogsync.CatalogSyncRepository().read(ozon,job).confirmed());
        repository.confirmAndSaveDispatch(wb,ozon,List.of(link),job);
        assertEquals("WB_OZON_RESUME_FROM_LINK_WORKFLOW",assertThrows(IllegalStateException.class,()->catalog.run(ozon,job,true)).getMessage());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM gtin_sync_jobs");var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
}
