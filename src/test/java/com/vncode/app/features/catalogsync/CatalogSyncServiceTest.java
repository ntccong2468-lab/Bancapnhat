package com.vncode.app.features.catalogsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static com.vncode.app.features.catalogsync.CatalogSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSyncServiceTest {
    @TempDir Path root;
    Shop shop;ProductSnapshot snapshot;GtinSyncModels.ProductKey key;GtinSyncRepository gtins;CatalogSyncRepository plans;
    CatalogSyncService service;Listing listing;Codes codes;GtinSyncModels.RegisteredGtin source;
    final List<String> barcodes=new ArrayList<>(List.of("legacy"));
    void setup(Marketplace marketplace)throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=new Shop(1,"Owner",marketplace,marketplace==Marketplace.OZON?"client":"","fixture");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'Owner','fixture',?,?)")){p.setString(1,marketplace.name());p.setString(2,shop.getClientId());p.executeUpdate();}
        key=new GtinSyncModels.ProductKey(1,marketplace,"123","variant");
        snapshot=new ProductSnapshot(new ProductKey(1,marketplace,"123","variant"),"live:old","Title","Keep","Brand","article","9",List.of(),List.of(new ProductAttribute("77","Состав",List.of("Old"),""),new ProductAttribute("88","ТН ВЭД",List.of("6103230000"),"")),List.of(),null,List.of(),Instant.now());
        source=new GtinSyncModels.RegisteredGtin("00000000000017","article","","",true,true,"National Catalog@fixture",Instant.now(),proofDetails(Map.of("material",List.of("Cotton"),"tnved",List.of("6103220000"))));
        gtins=new GtinSyncRepository();gtins.saveCatalog(1,marketplace,List.of(source));plans=new CatalogSyncRepository();listing=new Listing();codes=new Codes();
        var edits=new ProductBulkEditService(new ProductEditRepository(),(s,k)->snapshot,s->listing);
        service=new CatalogSyncService(plans,gtins,edits,(s,k)->snapshot,s->listing,k->codes,(s,g)->{},(s,selected)->{});
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void wbChangedGroupCannotPreviewOldRegisteredGtin()throws Exception{changedGroup(Marketplace.WILDBERRIES);}
    @Test void ozonChangedGroupCannotPreviewOldRegisteredGtin()throws Exception{changedGroup(Marketplace.OZON);}
    private void changeGroup(){snapshot=new ProductSnapshot(snapshot.key(),"live:group-changed",snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),List.of(new ProductAttribute("88","ТН ВЭД",List.of("6112110000"),"")),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),Instant.now());}
    private void changedGroup(Marketplace market)throws Exception{
        setup(market);changeGroup();var failure=assertThrows(IllegalArgumentException.class,()->service.preview(shop,List.of(new Selection(key,source.gtin())),new Options(Set.of(Field.GTIN),Map.of())));
        assertEquals("GTIN_TNVED_GROUP_CHANGED",failure.getMessage());assertEquals(0,codes.writes);assertEquals(0,listing.writes);
    }
    @Test void tnvedGroupIsRecheckedAfterPreviewBeforeDispatch()throws Exception{
        setup(Marketplace.WILDBERRIES);var plan=service.preview(shop,List.of(new Selection(key,source.gtin())),new Options(Set.of(Field.GTIN),Map.of()));changeGroup();
        var result=service.run(shop,plan.id(),true);assertEquals(State.BLOCKED,result.rows().getFirst().state());assertEquals("GTIN_TNVED_GROUP_CHANGED",result.rows().getFirst().issue());assertEquals(0,codes.writes);
    }
    @Test void metadataBeforeGtinWb()throws Exception{neverAddsGtinUntilAttributesWereReadBackAndPersistsRecovery(Marketplace.WILDBERRIES);}
    @Test void metadataBeforeGtinOzon()throws Exception{neverAddsGtinUntilAttributesWereReadBackAndPersistsRecovery(Marketplace.OZON);}
    @Test void lostRepliesWb()throws Exception{lostReadbackAndLostGtinResponseNeverReplayWrites(Marketplace.WILDBERRIES);}
    @Test void lostRepliesOzon()throws Exception{lostReadbackAndLostGtinResponseNeverReplayWrites(Marketplace.OZON);}
    @Test void sourceAndOwnerScopeWb()throws Exception{sourceChangesBlockBeforeFirstWriteAndCredentialsCannotReassignJob(Marketplace.WILDBERRIES);}
    @Test void sourceAndOwnerScopeOzon()throws Exception{sourceChangesBlockBeforeFirstWriteAndCredentialsCannotReassignJob(Marketplace.OZON);}
    @Test void isolatedQueueWb()throws Exception{readyPipelineDoesNotRunUnrelatedQueuedGtinJobs(Marketplace.WILDBERRIES);}
    @Test void isolatedQueueOzon()throws Exception{readyPipelineDoesNotRunUnrelatedQueuedGtinJobs(Marketplace.OZON);}
    Job preview()throws Exception{return service.preview(shop,List.of(new Selection(key,source.gtin())),new Options(Set.of(Field.GTIN,Field.MATERIAL,Field.TNVED),Map.of()));}
    @Test void wbPriceConsentIsExplicitDurableAndBoundToExactPlan()throws Exception{
        setup(Marketplace.WILDBERRIES);var old=preview();service.confirm(shop,old.id());
        assertFalse(new CatalogSyncRepository().priceConsent(shop,old.id()));
        assertEquals("WB_PRICE_CONSENT_REQUIRES_NEW_PLAN",assertThrows(IllegalArgumentException.class,()->service.confirm(shop,old.id(),true)).getMessage());
        var plan=preview();service.confirm(shop,plan.id(),true);
        assertTrue(new CatalogSyncRepository().priceConsent(shop,plan.id()));
        var row=plan.rows().getFirst();var altered=new Row(row.id(),row.key(),row.catalog(),List.of("changed"),row.differences(),row.selectedDocuments(),row.values(),row.editJob(),row.gtinJob(),row.state(),row.issue());
        plans.update(shop,plan.id(),altered);
        assertFalse(new CatalogSyncRepository().priceConsent(shop,plan.id()));
    }
    @Test void priceConsentCannotBeGrantedForOzonOrForPlanWithoutGtin()throws Exception{
        setup(Marketplace.OZON);var plan=preview();assertThrows(IllegalArgumentException.class,()->service.confirm(shop,plan.id(),true));assertFalse(plans.priceConsent(shop,plan.id()));
    }
    @Test void sellerConsentFlowsToPersistedGtinChildAndOldPlansNeverGainIt()throws Exception{
        setup(Marketplace.WILDBERRIES);listing.ready=true;
        var plan=preview();service.confirm(shop,plan.id(),true);var done=service.run(shop,plan.id(),false);
        var child=UUID.fromString(done.rows().getFirst().gtinJob());assertTrue(new GtinSyncRepository().hasWbPriceConsent(child));
        barcodes.clear();barcodes.add("legacy");barcodes.add("second-fixture-barcode");var old=preview();var oldDone=service.run(shop,old.id(),true);
        var oldChild=UUID.fromString(oldDone.rows().getFirst().gtinJob());assertFalse(new GtinSyncRepository().hasWbPriceConsent(oldChild));
    }
    void neverAddsGtinUntilAttributesWereReadBackAndPersistsRecovery(Marketplace marketplace)throws Exception {
        setup(marketplace);var p=preview();assertEquals(1,p.readyCount());assertEquals(0,listing.writes);assertEquals(0,codes.writes);
        assertThrows(IllegalArgumentException.class,()->service.run(shop,p.id(),false));
        var sent=service.run(shop,p.id(),true);assertEquals(State.ATTRIBUTES_PENDING,sent.rows().getFirst().state());assertEquals(1,listing.writes);assertEquals(0,codes.writes);
        assertTrue(service.read(shop,p.id()).confirmed());listing.ready=true;
        var done=service.run(shop,p.id(),false);assertEquals(State.SUCCEEDED,done.rows().getFirst().state());assertEquals(1,listing.writes);assertEquals(1,codes.writes);
        assertEquals(List.of("legacy",source.gtin()),barcodes);service.run(shop,p.id(),false);assertEquals(1,codes.writes);
    }
    void lostReadbackAndLostGtinResponseNeverReplayWrites(Marketplace marketplace)throws Exception {
        setup(marketplace);listing.lostRead=true;var p=preview();
        var uncertain=service.run(shop,p.id(),true);assertEquals(State.RECONCILE_REQUIRED,uncertain.rows().getFirst().state());assertEquals(1,listing.writes);assertEquals(0,codes.writes);
        listing.lostRead=false;listing.ready=true;codes.lostReply=true;
        assertEquals(State.SUCCEEDED,service.run(shop,p.id(),false).rows().getFirst().state());assertEquals(1,listing.writes);assertEquals(1,codes.writes);
    }
    void sourceChangesBlockBeforeFirstWriteAndCredentialsCannotReassignJob(Marketplace marketplace)throws Exception {
        setup(marketplace);var p=preview();
        gtins.saveCatalog(1,marketplace,List.of(new GtinSyncModels.RegisteredGtin(source.gtin(),source.article(),"","",true,true,source.source(),Instant.now(),CatalogProductData.EMPTY)));
        assertEquals(State.BLOCKED,service.run(shop,p.id(),true).rows().getFirst().state());assertEquals(0,listing.writes);assertEquals(0,codes.writes);
        var changed=new Shop(1,"Owner",marketplace,shop.getClientId(),"rotated");assertThrows(SecurityException.class,()->service.read(changed,p.id()));
    }
    void readyPipelineDoesNotRunUnrelatedQueuedGtinJobs(Marketplace marketplace)throws Exception {
        setup(marketplace);var otherKey=new GtinSyncModels.ProductKey(1,marketplace,"999","other");
        var unrelated=gtins.createJob(List.of(new GtinSyncModels.Preview(new GtinSyncModels.ProductSnapshot(otherKey,"other","","",List.of("legacy"),"other",Instant.now()),GtinSyncModels.Operation.ADD,"",source.gtin(),new GtinSyncModels.Capability(true,false,""))));
        listing.ready=true;var p=preview();assertEquals(State.SUCCEEDED,service.run(shop,p.id(),true).rows().getFirst().state());
        assertEquals(GtinSyncModels.Status.QUEUED,gtins.jobItems(unrelated).getFirst().status());assertEquals(1,codes.writes);
    }
    @Test void explicitSellerOverrideIsFrozenAndDoesNotRewriteCatalogProof()throws Exception{
        setup(Marketplace.WILDBERRIES);
        var options=new Options(Set.of(Field.MATERIAL),Map.of(),Map.of(key,Map.of("material",List.of("Seller value"))));
        var plan=service.preview(shop,List.of(new Selection(key,source.gtin())),options);
        assertEquals(List.of("Seller value"),listing.changes.get("attribute:77"));
        var frozen=service.read(shop,plan.id()).rows().getFirst();
        assertEquals("Cotton",frozen.catalog().details().single("material"));assertEquals(List.of("Seller value"),frozen.values().get("material"));
        assertEquals(State.ATTRIBUTES_PENDING,service.run(shop,plan.id(),true).rows().getFirst().state());
    }
    @Test void changedNoOpTargetRequiresAnotherPreviewWithoutSendingGtin()throws Exception{
        setup(Marketplace.OZON);
        source=new GtinSyncModels.RegisteredGtin(source.gtin(),"article","","",true,true,source.source(),Instant.now(),proofDetails(Map.of("material",List.of("Old"))));
        gtins.saveCatalog(1,shop.getMarketplace(),List.of(source));
        var plan=service.preview(shop,List.of(new Selection(key,source.gtin())),new Options(Set.of(Field.GTIN,Field.MATERIAL),Map.of()));
        assertEquals("",plan.rows().getFirst().editJob());
        snapshot=new ProductSnapshot(snapshot.key(),"live:external",snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),List.of(new ProductAttribute("77","Состав",List.of("External"),"")),snapshot.variants(),null,List.of(),Instant.now());
        var result=service.run(shop,plan.id(),true);assertEquals(State.BLOCKED,result.rows().getFirst().state());assertEquals("TARGET_CHANGED",result.rows().getFirst().issue());assertEquals(0,codes.writes);assertEquals(0,listing.writes);
    }
    @Test void aBlockedSiblingStopsTheSharedCardPatchForEveryVariant()throws Exception{
        setup(Marketplace.WILDBERRIES);
        var sibling=new GtinSyncModels.ProductKey(1,shop.getMarketplace(),"123","sibling");
        var plan=service.preview(shop,List.of(new Selection(key,source.gtin()),new Selection(sibling,source.gtin())),
            new Options(Set.of(Field.MATERIAL),Map.of(),Map.of(sibling,Map.of("material",List.of("Different")))));
        assertEquals(0,plan.readyCount());assertTrue(plan.rows().stream().allMatch(r->r.state()==State.BLOCKED&&r.editJob().isBlank()));assertEquals(0,listing.writes);
    }
    @Test void freshCatalogReadFailureBeforeDispatchPreservesCacheAndSendsNothing()throws Exception{
        setup(Marketplace.WILDBERRIES);var refreshed=new ArrayList<Set<String>>();
        service=withRefresh((s,selected)->{refreshed.add(Set.copyOf(selected));if(refreshed.size()==2)throw new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("fixture",401,"{}");});
        var plan=preview();var failure=assertThrows(com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException.class,()->service.run(shop,plan.id(),true));
        assertEquals(401,failure.statusCode());assertEquals(List.of(Set.of(source.gtin()),Set.of(source.gtin())),refreshed);assertEquals(source,gtins.catalogItem(1,shop.getMarketplace(),source.gtin()));assertEquals(State.READY,service.read(shop,plan.id()).rows().getFirst().state());assertEquals(0,listing.writes);assertEquals(0,codes.writes);
    }
    @Test void currentCatalogRevocationIsReadAgainBeforeAnyMarketplaceWrite()throws Exception{
        setup(Marketplace.OZON);var reads=new java.util.concurrent.atomic.AtomicInteger();
        service=withRefresh((s,selected)->{if(reads.incrementAndGet()==2){var details=proofDetails(Map.of("material",List.of("Cotton"),"tnved",List.of("6103220000")));var fields=new LinkedHashMap<>(details.fields());fields.put("@catalog_status",List.of("draft"));gtins.saveCatalog(1,s.getMarketplace(),List.of(new GtinSyncModels.RegisteredGtin(source.gtin(),source.article(),"","",true,false,source.source(),Instant.now(),new CatalogProductData(fields,List.of(),List.of()))));}});
        var plan=preview();var result=service.run(shop,plan.id(),true);assertEquals(2,reads.get());assertEquals(State.BLOCKED,result.rows().getFirst().state());assertEquals(0,listing.writes);assertEquals(0,codes.writes);
    }
    @Test void selectedRefreshRejectsMissingDuplicateAndForeignCardsBeforeMerge()throws Exception{
        setup(Marketplace.WILDBERRIES);assertDoesNotThrow(()->CatalogSyncService.requireSelectedObservations(Set.of(source.gtin()),List.of(source)));
        assertThrows(IllegalStateException.class,()->CatalogSyncService.requireSelectedObservations(Set.of(source.gtin()),List.of()));
        assertThrows(IllegalStateException.class,()->CatalogSyncService.requireSelectedObservations(Set.of(source.gtin()),List.of(source,source)));
        assertThrows(IllegalStateException.class,()->CatalogSyncService.requireSelectedObservations(Set.of("00000000000024"),List.of(source)));
        var unknown=new GtinSyncModels.RegisteredGtin(source.gtin(),source.article(),"","",true,true,source.source(),Instant.now());assertThrows(IllegalStateException.class,()->CatalogSyncService.requireSelectedObservations(Set.of(source.gtin()),List.of(unknown)));
        assertEquals(source,gtins.catalogItem(1,shop.getMarketplace(),source.gtin()));
    }
    @Test void foreignSelectionIsRejectedBeforeAnyCatalogRefreshOrAdapterRead()throws Exception{
        setup(Marketplace.WILDBERRIES);var calls=new java.util.concurrent.atomic.AtomicInteger();service=withRefresh((s,selected)->calls.incrementAndGet());
        var foreign=new GtinSyncModels.ProductKey(2,shop.getMarketplace(),"123","variant");assertThrows(SecurityException.class,()->service.preview(shop,List.of(new Selection(foreign,source.gtin())),new Options(Set.of(Field.GTIN),Map.of())));assertEquals(0,calls.get());assertEquals(0,codes.writes);
    }
    @Test void sevenArgumentProductionConstructorCannotSilentlySkipTheFreshCatalogGate()throws Exception{
        setup(Marketplace.WILDBERRIES);
        service=new CatalogSyncService(plans,gtins,new ProductBulkEditService(new ProductEditRepository(),(s,k)->snapshot,s->listing),(s,k)->snapshot,s->listing,k->codes,(s,g)->{});
        // This synthetic shop intentionally has no tested Catalog identity. The real
        // default gate must reject locally before HTTP, despite a permissive DI authority.
        var failure=assertThrows(RegisteredGtinProof.UnverifiedOwnerException.class,()->service.preview(shop,List.of(new Selection(key,source.gtin())),new Options(Set.of(Field.GTIN),Map.of())));
        assertEquals(RegisteredGtinProof.OwnerFailure.SIGNATURE_NOT_TESTED,failure.reason());assertEquals(0,codes.writes);assertEquals(0,listing.writes);assertEquals(source,gtins.catalogItem(1,shop.getMarketplace(),source.gtin()));
    }
    @Test void gtinCohortReadsCardsWithoutBlanketRegistryRequestsWhileDocumentScopeStillRequestsThem()throws Exception{
        setup(Marketplace.WILDBERRIES);var flags=new ArrayList<Boolean>();
        service=withRefresh(new CatalogSyncService.SourceRefresh(){
            public void refresh(Shop owner,Set<String> selected){throw new AssertionError("The operation must specify its document scope");}
            @Override public void refresh(Shop owner,Set<String> selected,boolean includeDocuments){assertEquals(Set.of(source.gtin()),selected);flags.add(includeDocuments);}
        });
        var plan=preview();assertEquals(State.ATTRIBUTES_PENDING,service.run(shop,plan.id(),true).rows().getFirst().state());
        service.preview(shop,List.of(new Selection(key,source.gtin())),new Options(Set.of(Field.DOCUMENTS),Map.of()));
        assertEquals(List.of(false,false,true),flags);assertEquals(0,codes.writes);
    }
    private CatalogSyncService withRefresh(CatalogSyncService.SourceRefresh refresh){return new CatalogSyncService(plans,gtins,new ProductBulkEditService(new ProductEditRepository(),(s,k)->snapshot,s->listing),(s,k)->snapshot,s->listing,k->codes,(s,g)->{},refresh);}
    private static CatalogProductData proofDetails(Map<String,List<String>> fields){var details=new LinkedHashMap<>(fields);details.put("@catalog_id",List.of("91"));details.put("@catalog_status",List.of("published"));return new CatalogProductData(details,List.of(),List.of());}
    class Listing implements ListingTargetAdapter {
        int writes;boolean ready,lostRead;Map<String,List<String>> changes;
        public List<TargetCategory> categories(Shop s){return List.of();}
        public List<TargetAttribute> attributes(Shop s,String c){return List.of(new TargetAttribute("77","Состав","1",false,"",10,"","",false),new TargetAttribute("88","ТН ВЭД","1",false,"",1,"","",false));}
        public CopyPreview preview(Shop s,ProductSnapshot f,CopyOptions o){throw new UnsupportedOperationException();}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> changes,List<ProductDocument> docs){
            this.changes=changes;String request=ProductJson.GSON.toJson(changes),owner=ListingIdentity.fingerprint(s);var o=new CopyOptions(changes.keySet(),Set.of(),f.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);
            return new ProductEditPreview(f.key(),f.revision(),new CopyPreview(s.getId(),s.getMarketplace(),owner,f.key(),f.revision(),CopyPreview.hash(request,"[]","[]",owner,f.revision(),"UPDATE"),request,"[]","[]",Map.of(),List.of(),List.of("article"),o,"UPDATE"));
        }
        public RemoteSubmission submit(Shop s,CopyPreview p){return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){writes++;return submit(s,p.preview());}
        public RemoteResult reconcile(Shop s,RemoteSubmission r)throws IOException {
            if(lostRead)throw new IOException("temporary fixture read failure");if(!ready)return RemoteResult.pending();
            snapshot=new ProductSnapshot(snapshot.key(),"live:updated",snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),List.of(new ProductAttribute("77","Состав",List.of("Cotton"),""),new ProductAttribute("88","ТН ВЭД",List.of("6103220000"),"")),snapshot.variants(),null,List.of(),Instant.now());
            return new RemoteResult(CopyState.RECONCILED,List.of("123"),List.of(),Map.of("article","123"));
        }
        public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){throw new AssertionError("No files in fixture");}
    }
    class Codes implements GtinMarketplaceAdapter {
        int writes;boolean lostReply;
        public GtinSyncModels.Capability capability(GtinSyncModels.ProductKey key){return new GtinSyncModels.Capability(true,false,"");}
        public GtinSyncModels.ProductSnapshot read(GtinSyncModels.ProductKey key){return new GtinSyncModels.ProductSnapshot(key,"article","","",barcodes,ListingIdentity.hash(snapshot.revision()+barcodes),Instant.now());}
        public void validateIdentity(GtinSyncModels.ProductSnapshot target,GtinSyncModels.RegisteredGtin registered){if(!target.article().equals(registered.article()))throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");}
        public boolean conflicts(GtinSyncModels.ProductKey key,String gtin){return false;}
        public GtinSyncModels.Submission submit(GtinSyncModels.Preview p)throws IOException {
            assertEquals("live:updated",snapshot.revision(),"Attributes must have completed readback before barcode submission");
            writes++;barcodes.add(p.newGtin());if(lostReply)throw new IOException("response lost after fixture commit");return new GtinSyncModels.Submission("");
        }
        public GtinSyncModels.Verification verify(GtinSyncModels.Preview p,GtinSyncModels.Submission s){return barcodes.contains(p.newGtin())?GtinSyncModels.Verification.APPLIED:GtinSyncModels.Verification.PENDING;}
    }
}
