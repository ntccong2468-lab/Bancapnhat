package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.listing.ListingTransport;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static com.vncode.app.features.gtinsync.GtinWriteTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class WbGtinAdapterTest {
    @TempDir Path root;
    Shop shop;Fixture transport;WbGtinAdapter adapter;
    final ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"101","11");
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=shop(Marketplace.WILDBERRIES);transport=new Fixture();adapter=new WbGtinAdapter(shop,transport);catalogProof("Fixture","Black","XL",false);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    void catalogProof(String article,String color,String size,boolean russian){
        var fields=new java.util.LinkedHashMap<String,List<String>>();fields.put("@catalog_id",List.of("91"));fields.put("@catalog_status",List.of("published"));if(russian)fields.put("@size_type",List.of("Россия"));
        var data=new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of());
        new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.WILDBERRIES,List.of(new RegisteredGtin(GTIN,article,color,size,true,true,RegisteredGtinProof.source(shop),Instant.now(),data)));
    }
    Preview preview()throws Exception{return new GtinPreviewService(new GtinSyncRepository(),k->adapter).create(key,Operation.ADD,"",GTIN);}
    @Test void physicalVariantProofRejectsWrongArticleColorOrSizeBeforeAnyPost()throws Exception{
        for(var values:List.of(List.of("Other model","Black","XL"),List.of("Fixture","Brown","XL"),List.of("Fixture","Black","L"))){
            catalogProof(values.get(0),values.get(1),values.get(2),false);
            assertEquals("GTIN_VARIANT_IDENTITY_UNVERIFIED",assertThrows(IllegalArgumentException.class,this::preview).getMessage());assertEquals(0,transport.writes.get());
        }
    }
    @Test void anObservedMarketplaceTnvedCannotBeWrittenWithAnUnverifiedOrDifferentRegisteredScope()throws Exception{
        transport.card.getAsJsonArray("characteristics").add(JsonParser.parseString("{\"id\":15000001,\"name\":\"ТН ВЭД\",\"value\":[\"6204420000\"]}"));
        assertEquals("GTIN_VARIANT_IDENTITY_UNVERIFIED",assertThrows(IllegalArgumentException.class,this::preview).getMessage());
        var row=new GtinSyncRepository().catalogItem(1,Marketplace.WILDBERRIES,GTIN);
        var fields=new java.util.LinkedHashMap<>(row.details().fields());fields.put("tnved",List.of("6204420000"));
        var same=new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of());
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin(row.gtin(),row.article(),row.color(),row.size(),true,true,row.source(),Instant.now(),same)));
        assertNotNull(preview());fields.put("tnved",List.of("6103109000"));
        var different=new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of());
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin(row.gtin(),row.article(),row.color(),row.size(),true,true,row.source(),Instant.now(),different)));
        assertEquals("GTIN_VARIANT_IDENTITY_UNVERIFIED",assertThrows(IllegalArgumentException.class,this::preview).getMessage());assertEquals(0,transport.writes.get());
    }
    @Test void registryRussiaSizeUsesTheObservedWbSizeWithoutConvertingTheManufacturerSize()throws Exception{
        catalogProof("Fixture","черный","50",true);var p=preview();var sent=adapter.submit(p);assertEquals(Verification.APPLIED,adapter.verify(p,sent));
        assertEquals("XL",p.before().size());assertEquals(1,transport.writes.get());
    }
    @Test void changingTheRegisteredVariantAfterPreviewBlocksTheFinalDispatch()throws Exception{
        var p=preview();transport.beforeSend=()->catalogProof("Fixture","Brown","XL",false);
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
    }
    @Test void cardAdditionalGtinAndActualFalseKizConfirmationRemainOutsideTheSkuAddition()throws Exception{
        transport.card.addProperty("gtin","00000000000024");transport.card.addProperty("kizMarked",false);
        adapter.submit(preview());var sent=transport.sent.get(0).getAsJsonObject();
        assertEquals("00000000000024",sent.get("gtin").getAsString());assertFalse(sent.get("kizMarked").getAsBoolean());
        assertEquals(List.of("legacy",GTIN),sent.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").asList().stream().map(JsonElement::getAsString).toList());
    }
    @Test void visibleBarcodeCacheChangesOnlyAfterActualReadbackNotAfterAnAcknowledgement()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,101,'Fixture','Fixture','fixture')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,nm_id,chrt_id,tech_size) VALUES(1,101,11,'L'),(1,101,12,'XL')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,12,'sibling')");
        }
        var plan=preview();transport.apply=false;var result=adapter.submit(plan);assertEquals(Verification.PENDING,adapter.verify(plan,result));assertFalse(cachedCodes().contains(GTIN));
        transport.card.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").add(GTIN);
        assertEquals(Verification.APPLIED,adapter.verify(plan,result));assertTrue(cachedCodes().contains(GTIN));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT sku FROM wb_product_size_skus WHERE shop_id=1 AND chrt_id=12")){assertTrue(r.next());assertEquals("sibling",r.getString(1));}
    }
    private List<String> cachedCodes()throws Exception{var values=new java.util.ArrayList<String>();try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT sku FROM wb_product_size_skus WHERE shop_id=1 AND chrt_id=11")){while(r.next())values.add(r.getString(1));}return values;}
    @Test void additionPreservesCompleteCardAndOtherSizeBarcodes()throws Exception {
        var p=preview();assertTrue(p.capability().add());assertFalse(p.capability().replace());
        var submitted=adapter.submit(p);assertEquals(1,transport.writes.get());
        var card=transport.sent.get(0).getAsJsonObject();
        assertEquals("Keep title",card.get("title").getAsString());assertTrue(card.get("kizMarked").getAsBoolean());
        assertEquals("Keep description",card.get("description").getAsString());
        assertEquals(List.of("legacy",GTIN),card.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals("other-size",card.getAsJsonArray("sizes").get(1).getAsJsonObject().getAsJsonArray("skus").get(0).getAsString());
        assertEquals("owned-document",card.getAsJsonObject("documents").getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(card.getAsJsonObject("dimensions").has("isValid"));assertFalse(card.getAsJsonObject("documents").has("overallVerdict"));
        assertFalse(card.getAsJsonObject("documents").getAsJsonArray("items").get(0).getAsJsonObject().has("verdict"));
        assertFalse(card.has("photos"));assertEquals(Verification.APPLIED,adapter.verify(p,submitted));
    }
    @Test void http200RequiresReadbackAndCannotDiscardOldCode()throws Exception {
        var p=preview();transport.apply=false;var submitted=adapter.submit(p);
        assertEquals(Verification.PENDING,adapter.verify(p,submitted));
        var codes=transport.card.getAsJsonArray("sizes").get(0).getAsJsonObject();var replaced=new JsonArray();replaced.add(GTIN);codes.add("skus",replaced);
        assertEquals(Verification.UNKNOWN,adapter.verify(p,submitted));
    }
    @Test void timeoutIsReconciledWithoutAnotherMutation()throws Exception {
        var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,requested->{});
        var job=runner.confirm(List.of(preview()));transport.loseResponse=true;
        runner.runPending(1,Marketplace.WILDBERRIES);repo.recoverInterrupted();runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(1,transport.writes.get());assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());
    }
    @Test void changedUntouchedFieldBlocksOverwrite()throws Exception {
        var p=preview();transport.card.addProperty("description","Changed outside app");
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
    }
    @Test void aSellerEditDuringTransportWaitCannotBeOverwrittenByASingleGtinWrite()throws Exception{
        var p=preview();transport.beforeSend=()->transport.card.addProperty("description","Seller changed while rate-limited");
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
        assertEquals("Seller changed while rate-limited",transport.card.get("description").getAsString());
    }
    @Test void aSellerEditDuringTransportWaitBlocksTheEntireFullCardBatchBeforeItsBoundary()throws Exception{
        var p=preview();var boundary=new AtomicInteger();
        transport.beforeSend=()->transport.card.getAsJsonArray("sizes").get(1).getAsJsonObject().addProperty("wbSize","Seller's new size");
        assertThrows(GtinKnownUnsentException.class,()->adapter.submitBatch(List.of(p),boundary::incrementAndGet));
        assertEquals(0,boundary.get());assertEquals(0,transport.writes.get());
        assertEquals("Seller's new size",transport.card.getAsJsonArray("sizes").get(1).getAsJsonObject().get("wbSize").getAsString());
    }
    @Test void aLastMomentReservationConflictCannotCreateADispatchMarker()throws Exception{
        var currentCard=transport.card.deepCopy();var writes=new AtomicInteger();var marker=new AtomicInteger();
        var indexed=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.integration.listing.GtinBatchTransport>();
        var competing=new java.util.concurrent.atomic.AtomicReference<Preview>();
        var wire=new ListingTransport(){
            public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{
                if(mutation){writes.incrementAndGet();return JsonParser.parseString("{\"error\":false}");}
                var result=new JsonObject();var cards=new JsonArray();if(!path.endsWith("/trash"))cards.add(currentCard.deepCopy());result.add("cards",cards);
                var cursor=new JsonObject();cursor.addProperty("total",cards.size());cursor.addProperty("updatedAt","2026-10-21T00:00:00Z");cursor.addProperty("nmID",101);result.add("cursor",cursor);return result;
            }
            @Override public JsonElement call(String path,JsonElement body,boolean mutation,CheckedGuard fresh,Runnable guard)throws IOException{
                fresh.check();indexed.get().reserve(List.of(competing.get()));guard.run();return call(path,body,mutation);
            }
        };
        try(var batch=new com.vncode.app.integration.listing.GtinBatchTransport(shop,wire)){
            indexed.set(batch);var target=new WbGtinAdapter(shop,batch);var other=new ProductKey(1,Marketplace.WILDBERRIES,"101","12");target.prepareBatch(List.of(key,other));
            var p=new Preview(target.read(key),Operation.ADD,"",GTIN,target.capability(key));competing.set(new Preview(target.read(other),Operation.ADD,"",GTIN,target.capability(other)));
            assertThrows(GtinKnownUnsentException.class,()->target.submitBatch(List.of(p),marker::incrementAndGet));
            assertEquals(0,marker.get());assertEquals(0,writes.get());
        }
    }
    @Test void aFailedFinalReadCannotSendAnOldFullCard()throws Exception{
        var p=preview();transport.beforeSend=()->transport.readFailure=new IOException("fresh final read unavailable");
        assertThrows(GtinKnownUnsentException.class,()->adapter.submitBatch(List.of(p),()->{}));assertEquals(0,transport.writes.get());
    }
    @Test void barcodeOnTheSameIdWithChangedPhysicalVariantFactsIsNotVerifiedAsApplied()throws Exception{
        var p=preview();var sent=adapter.submit(p);
        transport.card.getAsJsonArray("sizes").get(0).getAsJsonObject().addProperty("techSize","XXL");
        assertEquals(Verification.UNKNOWN,adapter.verify(p,sent));assertEquals(Verification.UNKNOWN,adapter.verifyBatch(List.of(awaiting(p))).get(key).outcome());
        transport.card.getAsJsonArray("sizes").get(0).getAsJsonObject().addProperty("techSize",p.before().size());
        transport.card.getAsJsonArray("characteristics").get(0).getAsJsonObject().add("value",JsonParser.parseString("[\"Brown\"]"));
        assertEquals(Verification.UNKNOWN,adapter.verify(p,sent));assertEquals(Verification.UNKNOWN,adapter.verifyBatch(List.of(awaiting(p))).get(key).outcome());assertEquals(1,transport.writes.get());
    }
    @Test void aReadFailureAfterTheAcceptedPostRemainsUnknownAndNeverPostsAgain()throws Exception{
        var repository=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repository,k->adapter,Clock.systemUTC(),1,Duration.ZERO,requested->{});
        var job=runner.confirm(List.of(preview()));transport.failReadAfterWrite=true;runner.runJob(job);
        assertEquals(Status.RECONCILE_REQUIRED,repository.jobItems(job).getFirst().status());assertEquals(1,transport.writes.get());
        runner.runJob(job);assertEquals(Status.RECONCILE_REQUIRED,repository.jobItems(job).getFirst().status());assertEquals(1,transport.writes.get());
        transport.readFailure=null;runner.runJob(job);assertEquals(Status.SUCCEEDED,repository.jobItems(job).getFirst().status());assertEquals(1,transport.writes.get());
    }
    @Test void nativeCharacteristicReorderingDoesNotInvalidateAnOtherwiseUnchangedGtinPreview()throws Exception {
        var first=transport.card.getAsJsonArray("characteristics").get(0);var second=JsonParser.parseString("{\"id\":88,\"name\":\"Composition\",\"value\":[\"Cotton\",\"Polyester\"]}");
        transport.card.getAsJsonArray("characteristics").add(second);var p=preview();
        var reordered=new JsonArray();reordered.add(second);reordered.add(first);transport.card.add("characteristics",reordered);
        assertEquals(p.before().fingerprint(),adapter.read(key).fingerprint());var submitted=adapter.submit(p);
        assertEquals(1,transport.writes.get());assertEquals(Verification.APPLIED,adapter.verify(p,submitted));
        assertEquals(List.of("Cotton","Polyester"),transport.sent.get(0).getAsJsonObject().getAsJsonArray("characteristics").get(0).getAsJsonObject().getAsJsonArray("value").asList().stream().map(JsonElement::getAsString).toList());
    }
    @Test void sameGtinOnAnotherSizeIsAConflict()throws Exception {
        var foreign=transport.card.deepCopy();var codes=new JsonArray();codes.add("00000017");foreign.getAsJsonArray("sizes").get(1).getAsJsonObject().add("skus",codes);
        transport.conflict=foreign;
        assertTrue(adapter.conflicts(key,GTIN));assertThrows(IllegalArgumentException.class,this::preview);assertEquals(0,transport.writes.get());
    }
    @Test void aContradictoryOrMissingConflictPageCannotProveTheGtinIsUnused()throws Exception{
        for(boolean missing:new boolean[]{true,false}){
            ListingTransport incomplete=(path,body,mutation)->{
                assertFalse(mutation);var response=new JsonObject();response.add("cards",new JsonArray());
                if(!missing){var cursor=new JsonObject();cursor.addProperty("total",100);response.add("cursor",cursor);}return response;
            };
            assertEquals("incomplete_wb_catalog",assertThrows(IOException.class,()->new WbGtinAdapter(shop,incomplete).conflicts(key,GTIN)).getMessage());
        }
        assertEquals(0,transport.writes.get());
    }
    @Test void oldUnscopedRegistryCacheCannotAuthorizePreview()throws Exception {
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin(GTIN,"Fixture","","",true,true,"National Catalog",Instant.now())));
        assertEquals("unverified_catalog_gtin",assertThrows(IllegalArgumentException.class,this::preview).getMessage());assertEquals(0,transport.writes.get());
    }
    @Test void ownerChangedAfterTransportWaitIsKnownUnsentAndPaused()throws Exception {
        var repo=new GtinSyncRepository();var p=preview();
        var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,requested->{});var job=runner.confirm(List.of(p));
        transport.beforeSend=()->saveSettings(shop,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(0,transport.writes.get());assertEquals(Status.QUEUED,repo.jobItems(job).getFirst().status());assertTrue(repo.paused(job));
    }
    @Test void incompleteFullCardAndForeignShopNeverWrite() {
        transport.card.remove("dimensions");assertThrows(IOException.class,()->adapter.read(key));
        assertThrows(IllegalArgumentException.class,()->adapter.read(new ProductKey(2,Marketplace.WILDBERRIES,"101","11")));assertEquals(0,transport.writes.get());
    }
    @Test void explicitRejectionIsBoundToReadbackInsteadOfResend()throws Exception {
        var p=preview();transport.apply=false;transport.reject=true;var submitted=adapter.submit(p);
        assertEquals(Verification.REJECTED,adapter.verify(p,submitted));assertEquals(1,transport.writes.get());
    }
    @Test void confirmedGtinAdditionTrimsOnlyArticleEdgesAndRequiresReadback()throws Exception {
        transport.card.addProperty("vendorCode","\u00a0  Fixture - model  \u202f");catalogProof("Fixture - model","Black","XL",false);assertTrue(adapter.capability(key).add());
        var p=preview();assertEquals(0,transport.writes.get());
        var sent=adapter.submit(p);assertEquals(1,transport.writes.get());
        assertEquals("Fixture - model",transport.sent.get(0).getAsJsonObject().get("vendorCode").getAsString());
        assertEquals(Verification.APPLIED,adapter.verify(p,sent));
        transport.card.addProperty("vendorCode"," Fixture - model ");assertEquals(Verification.UNKNOWN,adapter.verify(p,sent));
    }
    @Test void articleTrimCannotTakeAnotherCardsArticle()throws Exception {
        transport.card.addProperty("vendorCode"," Fixture ");transport.conflict=transport.card.deepCopy();transport.conflict.addProperty("nmID",102);transport.conflict.addProperty("vendorCode","Fixture");
        assertEquals("WB_ARTICLE_TRIM_COLLISION",assertThrows(IllegalArgumentException.class,this::preview).getMessage());assertEquals(0,transport.writes.get());
    }
    @Test void aFreshNativeProductErrorEndsTheAcknowledgedJobWithoutReplayingIt()throws Exception {
        var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,requested->{});var job=runner.confirm(List.of(preview()));transport.apply=false;
        transport.errors=errorList("Fixture",Instant.now().plusSeconds(1));runner.runJob(job);repo.recoverInterrupted();runner.runJob(job);
        var item=repo.jobItems(job).getFirst();assertEquals(Status.FAILED,item.status());assertEquals("WB_GTIN_CARD_REJECTED",repo.issue(item.id()));assertEquals(1,transport.writes.get());
    }
    @Test void aCardErrorAfterPreviewButBeforeActualDispatchCannotRejectTheNewRequest()throws Exception{
        var fresh=preview();var before=fresh.before();
        var stale=new Preview(new ProductSnapshot(before.key(),before.article(),before.color(),before.size(),before.barcodes(),before.fingerprint(),Instant.now().minusSeconds(3600)),fresh.operation(),fresh.oldGtin(),fresh.newGtin(),fresh.capability());
        transport.apply=false;var sent=adapter.submit(stale);assertNotNull(sent.dispatchedAt());
        transport.errors=errorList("Fixture",sent.dispatchedAt().minusSeconds(60));
        assertEquals(Verification.PENDING,adapter.verify(stale,sent));assertEquals(1,transport.writes.get());
    }
    @Test void aLegacyJobWithoutDurableDispatchEvidenceCannotAttributeACardError()throws Exception{
        var p=preview();transport.errors=errorList("Fixture",Instant.now());
        var legacy=new JobItem(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),p,Status.RECONCILE_REQUIRED,"");
        assertEquals(Verification.PENDING,adapter.verifyBatch(List.of(legacy)).get(key).outcome());assertEquals(0,transport.writes.get());
    }
    @Test void staleOrAnotherArticlesErrorsCannotRejectTheCurrentGtinWrite()throws Exception {
        var p=preview();transport.apply=false;var sent=adapter.submit(p);
        transport.errors=errorList("Fixture",p.before().fetchedAt().minusSeconds(60));assertEquals(Verification.PENDING,adapter.verify(p,sent));
        transport.errors=errorList("Foreign",Instant.now().plusSeconds(1));assertEquals(Verification.PENDING,adapter.verify(p,sent));assertEquals(1,transport.writes.get());
    }
    @Test void originalEdgeArticleRejectionEndsTheAcknowledgedBatchWithWhitespaceDetail()throws Exception {
        String original=" Fixture - model \u00a0";transport.card.addProperty("vendorCode",original);catalogProof("Fixture - model","Black","XL",false);
        var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,requested->{});var job=runner.confirm(List.of(preview()));transport.apply=false;
        transport.errors=errorList(original,Instant.now().plusSeconds(1),"В Артикуле продавца содержится пробел. Пожалуйста, удалите пробел.");
        runner.runJob(job);repo.recoverInterrupted();runner.runJob(job);
        var item=repo.jobItems(job).getFirst();assertEquals(Status.FAILED,item.status());assertEquals("WB_ARTICLE_EDGE_WHITESPACE",repo.issue(item.id()));assertEquals(1,transport.writes.get());
        assertEquals("Fixture - model",transport.sent.get(0).getAsJsonObject().get("vendorCode").getAsString());
    }
    @Test void singleRejectionAcceptsExactOriginalAndSubmittedArticlesAndClassifiesTheRawSource()throws Exception {
        String original=" Fixture - model ";transport.card.addProperty("vendorCode",original);catalogProof("Fixture - model","Black","XL",false);var p=preview();transport.apply=false;var sent=adapter.submit(p);
        transport.errors=errorList(original,Instant.now().plusSeconds(1),"В Артикуле продавца содержится пробел.");
        assertEquals("WB_ARTICLE_EDGE_WHITESPACE",assertThrows(GtinRejectedException.class,()->adapter.verify(p,sent)).code());
        transport.errors=errorList("Fixture - model",Instant.now().plusSeconds(1),"В Артикуле продавца содержится пробел.");
        assertEquals("WB_GTIN_CARD_REJECTED",assertThrows(GtinRejectedException.class,()->adapter.verify(p,sent)).code());
        transport.errors=errorList(original,Instant.now().plusSeconds(1));assertEquals("WB_GTIN_CARD_REJECTED",assertThrows(GtinRejectedException.class,()->adapter.verify(p,sent)).code());assertEquals(1,transport.writes.get());
    }
    @Test void batchRejectionAcceptsTheExactSubmittedArticleWhilePreservingInternalSpaces()throws Exception {
        transport.card.addProperty("vendorCode"," Fixture - model ");catalogProof("Fixture - model","Black","XL",false);var p=preview();transport.apply=false;adapter.submitBatch(List.of(p),()->{});
        transport.errors=errorList("Fixture - model",Instant.now().plusSeconds(1));var result=adapter.verifyBatch(List.of(awaiting(p))).get(key);
        assertEquals(Verification.REJECTED,result.outcome());assertEquals("WB_GTIN_CARD_REJECTED",result.issue());assertEquals(1,transport.writes.get());
    }
    @Test void originalArticleRejectionCannotMatchAnotherEdgeSpellingOldTimeOrFutureTime()throws Exception {
        String original=" Fixture - model ";transport.card.addProperty("vendorCode",original);catalogProof("Fixture - model","Black","XL",false);var p=preview();transport.apply=false;var sent=adapter.submit(p);
        transport.errors=errorList("  Fixture - model",Instant.now().plusSeconds(1));assertPending(p,sent);
        transport.errors=errorList("Fixture-model",Instant.now().plusSeconds(1));assertPending(p,sent);
        transport.errors=errorList(original,p.before().fetchedAt().minusSeconds(1));assertPending(p,sent);
        transport.errors=errorList(original,Instant.now().plusSeconds(600));assertPending(p,sent);assertEquals(1,transport.writes.get());
    }
    @Test void originalArticleAliasRequiresTheUnchangedOwnedPreimage()throws Exception {
        String original=" Fixture - model ";transport.card.addProperty("vendorCode",original);catalogProof("Fixture - model","Black","XL",false);var p=preview();transport.apply=false;var sent=adapter.submit(p);
        transport.card.addProperty("description","Changed after acknowledgement");transport.errors=errorList(original,Instant.now().plusSeconds(1));assertPending(p,sent);
    }
    @Test void rejectionOfAnUnverifiedSiblingDoesNotOverrideVerifiedReadback()throws Exception {
        var first=preview();var secondKey=new ProductKey(1,Marketplace.WILDBERRIES,"101","12");
        var second=new Preview(adapter.read(secondKey),Operation.ADD,"","00000000000024",adapter.capability(secondKey));
        transport.card.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").add(GTIN);
        transport.errors=errorList("Fixture",Instant.now().plusSeconds(1));var result=adapter.verifyBatch(List.of(awaiting(first),awaiting(second)));
        assertEquals(Verification.APPLIED,result.get(key).outcome());assertEquals("",result.get(key).issue());assertEquals(Verification.REJECTED,result.get(secondKey).outcome());assertEquals("WB_GTIN_CARD_REJECTED",result.get(secondKey).issue());assertEquals(0,transport.writes.get());
    }
    @Test void anArticleAliasSharedByDifferentProductOwnersCannotAttributeARejection()throws Exception {
        transport.card.addProperty("vendorCode"," Fixture - model ");transport.extra=transport.card.deepCopy();transport.extra.addProperty("nmID",102);transport.extra.addProperty("vendorCode","Fixture - model");
        transport.extra.getAsJsonArray("sizes").get(0).getAsJsonObject().addProperty("chrtID",21);transport.extra.getAsJsonArray("sizes").get(1).getAsJsonObject().addProperty("chrtID",22);
        var otherKey=new ProductKey(1,Marketplace.WILDBERRIES,"102","21");
        var first=new Preview(adapter.read(key),Operation.ADD,"",GTIN,adapter.capability(key));var second=new Preview(adapter.read(otherKey),Operation.ADD,"","00000000000024",adapter.capability(otherKey));
        transport.errors=errorList("Fixture - model",Instant.now().plusSeconds(1));var result=adapter.verifyBatch(List.of(awaiting(first),awaiting(second)));
        assertEquals(Verification.PENDING,result.get(key).outcome());assertEquals(Verification.PENDING,result.get(otherKey).outcome());assertEquals(0,transport.writes.get());
    }
    private void assertPending(Preview p,Submission sent)throws Exception{assertEquals(Verification.PENDING,adapter.verify(p,sent));assertEquals(Verification.PENDING,adapter.verifyBatch(List.of(awaiting(p))).get(p.before().key()).outcome());}
    private static JobItem awaiting(Preview p){return new JobItem(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),p,Status.AWAITING_VERIFICATION,"",Instant.now());}
    private static JsonElement errorList(String article,Instant at){return errorList(article,at,"Invalid fixture attributes");}
    private static JsonElement errorList(String article,Instant at,String reason){var item=new JsonObject();var codes=new JsonArray();codes.add(article);item.add("vendorCodes",codes);item.addProperty("updatedAt",at.toString());var errors=new JsonObject();var messages=new JsonArray();messages.add(reason);errors.add(article,messages);item.add("errors",errors);var items=new JsonArray();items.add(item);var data=new JsonObject();data.add("items",items);var root=new JsonObject();root.add("data",data);return root;}
    static final class Fixture implements ListingTransport {
        JsonObject card=JsonParser.parseString("""
          {"nmID":101,"subjectID":77,"vendorCode":"Fixture","title":"Keep title","description":"Keep description","brand":"Keep brand","kizMarked":true,
          "dimensions":{"length":12,"width":13,"height":14,"weightBrutto":0.123,"isValid":true},
          "characteristics":[{"id":99,"name":"Color","value":["Black"]}],
          "sizes":[{"chrtID":11,"techSize":"XL","wbSize":"50","skus":["legacy"]},{"chrtID":12,"techSize":"L","wbSize":"48","skus":["other-size"]}],
          "documents":{"items":[{"id":"owned-document","type":2,"number":"D","applicant":"Fixture","verdict":{"verified":true,"status":1}}],"overallVerdict":{"verified":true}},"photos":[{"big":"https://example.invalid/fixture.jpg"}]}
          """).getAsJsonObject();
        JsonObject conflict,extra;JsonArray sent;JsonElement errors=JsonParser.parseString("{\"data\":{\"items\":[],\"cursor\":{\"next\":false}}}");AtomicInteger writes=new AtomicInteger();boolean apply=true,loseResponse,reject,failReadAfterWrite;IOException readFailure;Runnable beforeSend=()->{};
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){assertEquals("content/v2/cards/update",path);writes.incrementAndGet();sent=body.getAsJsonArray().deepCopy();if(apply){var updated=sent.get(0).getAsJsonObject().deepCopy();for(var next:updated.getAsJsonArray("characteristics"))for(var old:card.getAsJsonArray("characteristics"))if(next.getAsJsonObject().get("id").equals(old.getAsJsonObject().get("id"))&&old.getAsJsonObject().has("name"))next.getAsJsonObject().add("name",old.getAsJsonObject().get("name"));card=updated;}if(failReadAfterWrite)readFailure=new IOException("Listing API HTTP 401");if(loseResponse)throw new IOException("fixture timeout");return JsonParser.parseString(reject?"{\"error\":true}":"{\"error\":false}");}
            if(readFailure!=null)throw readFailure;
            if(path.equals("content/v2/cards/error/list"))return errors.deepCopy();
            assertEquals("content/v2/get/cards/list",path);String search=body.getAsJsonObject().getAsJsonObject("settings").getAsJsonObject("filter").get("textSearch").getAsString();
            var cards=new JsonArray();if(search.equals("101"))cards.add(card.deepCopy());else if(extra!=null&&search.equals(extra.get("nmID").getAsString()))cards.add(extra.deepCopy());else if(conflict!=null)cards.add(conflict.deepCopy());var out=new JsonObject();out.add("cards",cards);var cursor=new JsonObject();cursor.addProperty("total",cards.size());cursor.addProperty("updatedAt","2026-10-21T00:00:00Z");cursor.addProperty("nmID",101);out.add("cursor",cursor);return out;
        }
        public JsonElement call(String path,JsonElement body,boolean mutation,Runnable guard)throws IOException{beforeSend.run();guard.run();return call(path,body,mutation);}
    }
}
