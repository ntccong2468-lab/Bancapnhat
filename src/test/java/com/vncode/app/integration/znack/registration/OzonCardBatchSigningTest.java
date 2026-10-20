package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Catalog responses only. Every signature POST still goes through the production service. */
class OzonCardBatchSigningTest {
    @TempDir Path root;
    Shop shop;OzonCardRegistrationRepository repository;FixtureApi api;OzonCardRegistrationWorkflow workflow;
    final Map<Long,ProductKey> keys=new LinkedHashMap<>();
    final Map<String,Long> identities=new LinkedHashMap<>();
    Runnable duringSigning=()->{};

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);repository=new OzonCardRegistrationRepository();api=new FixtureApi();
        workflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->{throw new AssertionError("Signing must not read or rewrite marketplace products");},Runnable::run,(s,k,sku,gtin)->false);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ZnackCardRegistrationWorkflow.CatalogSession session(){
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture-token";}};
        var catalog=new ZnackNationalCatalogService(api,auth,(xml,context)->{duringSigning.run();return new CryptoProSigningResult(new byte[]{1},"");},settings);
        return new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,ZnackRegistrationScope.fromSettings(settings));
    }
    List<ProductKey> seed(int count,boolean consent){
        var scope=session().scope();var draft=new Draft("6204",1,"Fixture","Brand",Map.of());
        for(long id=1;id<=count;id++){
            var key=new ProductKey(shop.getId(),Marketplace.OZON,Long.toString(500+id),"offer-"+id);String gtin=gtin(id);
            keys.put(id,key);identities.put(gtin,id);
            assertTrue(repository.claim(shop,key,Long.toString(9000+id),"",scope,false));assertTrue(repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
            repository.generated(key,gtin,ZnackNationalCatalogService.buildPayload(gtin,draft,"").toString());
            assertTrue(repository.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING));repository.submitted(key,"feed-"+id);
            repository.progress(key,Status.READY_TO_SIGN,id);RegistrationSigningConsent.set(shop,key,gtin,consent);
        }
        return List.copyOf(keys.values());
    }
    static String gtin(long id){
        String digits=String.format(Locale.ROOT,"%013d",id);int sum=0,weight=3;
        for(int at=digits.length()-1;at>=0;at--){sum+=(digits.charAt(at)-'0')*weight;weight=4-weight;}
        return digits+(10-sum%10)%10;
    }
    @Test void twentyFiveApprovedCardsUseThreeRealArrayPostsOfTenTenFive(){
        var selected=seed(25,false);assertTrue(workflow.signApproved(shop,selected,true,null));
        assertEquals(List.of(10,10,5),api.batchSizes);assertEquals(25,api.signedDocuments);
        for(var key:selected){var row=repository.find(key);assertEquals(Status.PUBLISHED,row.status());assertEquals("CONFIRMED",row.signatureState());assertTrue(RegistrationSigningConsent.allowed(shop,key,row.gtin()));}
    }
    @Test void signatureReceiptAloneNeverMarksCardsPublished()throws Exception{
        var selected=seed(3,true);api.publishOnPost=false;assertTrue(workflow.signConsented(shop,selected,null));
        assertEquals(List.of(3),api.batchSizes);
        for(var key:selected){assertEquals(Status.SIGNING,repository.find(key).status());assertEquals("RECONCILE_REQUIRED",repository.find(key).signatureState());}
        assertTrue(workflow.signConsented(shop,selected,null));assertEquals(List.of(3),api.batchSizes);
        api.published.addAll(keys.keySet());for(var key:selected)assertTrue(workflow.refreshStatusWithoutSigning(shop,key));
        assertEquals(List.of(3),api.batchSizes);
    }
    @Test void unknownArrayPostRetainsEveryFrozenDocumentAndNeverReplaysIt(){
        var selected=seed(10,true);api.timeout=true;assertTrue(workflow.signConsented(shop,selected,null));
        var hashes=new HashMap<ProductKey,String>();
        for(var key:selected){var row=repository.find(key);assertEquals(Status.SIGNING,row.status());assertEquals("RECONCILE_REQUIRED",row.signatureState());assertTrue(row.signatureXml().length>0);hashes.put(key,row.signatureHash());}
        api.timeout=false;assertTrue(workflow.signConsented(shop,selected,null));assertEquals(List.of(10),api.batchSizes);
        for(var key:selected)assertEquals(hashes.get(key),repository.find(key).signatureHash());
        api.published.addAll(keys.keySet());assertTrue(workflow.signConsented(shop,selected,null));
        for(var key:selected)assertEquals(Status.PUBLISHED,repository.find(key).status());assertEquals(List.of(10),api.batchSizes);
    }
    @Test void oneMissingSigningDocumentDoesNotBlockTheOtherSelectedCards(){
        var selected=seed(3,true);api.missingDocument=2;assertTrue(workflow.signConsented(shop,selected,null));
        assertEquals(List.of(2),api.batchSizes);assertEquals(Set.of(1L,3L),api.published);
        assertNotEquals(Status.PUBLISHED,repository.find(keys.get(2L)).status());
        assertTrue(repository.find(keys.get(2L)).error().contains("CATALOG_SIGNING_DOCUMENT_PENDING"));
    }
    @Test void documentDisappearingDuringCollectiveRecheckOnlySkipsThatMember(){
        var selected=seed(3,true);api.missingOnRecheck=2;assertTrue(workflow.signConsented(shop,selected,null));
        assertEquals(List.of(2),api.batchSizes);assertEquals(Set.of(1L,3L),api.published);
        assertEquals("PREPARED",repository.find(keys.get(2L)).signatureState());
    }
    @Test void changedXmlInOneCardCannotBeSignedWithItsOldCheckpoint(){
        var selected=seed(3,true);api.changedOnRecheck=2;assertTrue(workflow.signConsented(shop,selected,null));
        assertEquals(List.of(2),api.batchSizes);assertEquals(Set.of(1L,3L),api.published);
        var changed=repository.find(keys.get(2L));assertEquals(Status.READY_TO_SIGN,changed.status());assertEquals("PREPARED",changed.signatureState());assertTrue(changed.error().contains("SIGNING_DOCUMENT_CHANGED"));
    }
    @Test void rateLimitedFirstPostStopsBeforeAnyLaterGroup(){
        var selected=seed(25,true);api.rateLimited=true;assertTrue(workflow.signConsented(shop,selected,null));
        assertEquals(List.of(10),api.batchSizes);
        for(long id=1;id<=25;id++)assertEquals(id<=10?Status.SIGNING:Status.READY_TO_SIGN,repository.find(keys.get(id)).status());
        assertEquals(10,api.signedDocuments);assertTrue(api.published.isEmpty());
        assertNotNull(RegistrationCatalogCooldown.warning(shop));assertFalse(workflow.signConsented(shop,selected,null));assertEquals(List.of(10),api.batchSizes);
    }
    @Test void legacyOneCardRateLimitHandsOffCooldownWithoutAnImmediateReadback(){
        var selected=seed(1,true);api.rateLimited=true;
        assertThrows(ZnackApiClient.ZnackApiException.class,()->workflow.refreshStatus(shop,selected.getFirst()));
        var row=repository.find(selected.getFirst());assertEquals(Status.SIGNING,row.status());assertEquals("RECONCILE_REQUIRED",row.signatureState());
        assertNotNull(RegistrationCatalogCooldown.warning(shop));assertEquals(api.readsAtPost,api.statusReads,"A 429 must stop before another Catalog GET");
    }
    @Test void automaticAggregationUsesOnlyExistingExactCardConsent(){
        var selected=seed(3,false);var allowed=repository.find(keys.get(3L));RegistrationSigningConsent.set(shop,allowed.key(),allowed.gtin(),true);
        assertTrue(workflow.signConsented(shop,selected,null));assertEquals(List.of(1),api.batchSizes);assertEquals(Set.of(3L),api.published);
        for(long id=1;id<=2;id++){var row=repository.find(keys.get(id));assertEquals(Status.READY_TO_SIGN,row.status());assertFalse(RegistrationSigningConsent.allowed(shop,row.key(),row.gtin()));}
    }
    @Test void revokedConsentDuringCryptographicSigningCannotReachThePost(){
        var selected=seed(3,true);var revoked=repository.find(keys.get(2L));
        duringSigning=()->RegistrationSigningConsent.set(shop,revoked.key(),revoked.gtin(),false);
        assertTrue(workflow.signConsented(shop,selected,null));assertEquals(List.of(2),api.batchSizes);assertEquals(Set.of(1L,3L),api.published);
        assertFalse(RegistrationSigningConsent.allowed(shop,revoked.key(),revoked.gtin()));assertEquals("PREPARED",repository.find(revoked.key()).signatureState());
    }
    final class FixtureApi extends ZnackApiClient {
        final List<Integer> batchSizes=new ArrayList<>();final Set<Long> published=new HashSet<>();final Map<Long,Integer> documentReads=new HashMap<>();
        long missingDocument,missingOnRecheck,changedOnRecheck;boolean timeout,rateLimited,publishOnPost=true;int signedDocuments,statusReads,readsAtPost;
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){
            statusReads++;
            long id=Long.parseLong(feed.substring("feed-".length()));
            return json("{\"result\":{\"status\":\"Moderated\",\"item\":[{\"gtin\":\""+gtin(id)+"\",\"good_id\":"+id+"}]}}");
        }
        @Override public JsonElement productCards(String base,String token,String gtin){
            long id=identities.get(gtin);boolean complete=published.contains(id);
            return json("{\"result\":[{\"gtin\":\""+gtin+"\",\"good_id\":"+id+",\"good_status\":\""+(complete?"published":"draft")+"\",\"good_detailed_status\":[\""+(complete?"published":"notsigned")+"\"]}]}");
        }
        @Override public JsonElement nationalCatalogSigningDocument(String base,String token,JsonObject body)throws IOException {
            long id=identities.get(body.getAsJsonArray("gtins").get(0).getAsString());int count=documentReads.merge(id,1,Integer::sum);
            if(id==missingDocument||id==missingOnRecheck&&count>=2)throw new ZnackApiException("fixture document missing",404,"{}");
            var document=new JsonObject();document.addProperty("goodId",id);document.addProperty("xml",xml(id,id==changedOnRecheck&&count>=2));
            var docs=new JsonArray();docs.add(document);var result=new JsonObject();result.add("xmls",docs);var root=new JsonObject();root.add("result",result);return root;
        }
        @Override public JsonElement signNationalCatalogProduct(String base,String token,JsonArray body)throws IOException {
            assertTrue(body.size()>0&&body.size()<=10);batchSizes.add(body.size());signedDocuments+=body.size();readsAtPost=statusReads;var signed=new JsonArray();
            for(var entry:body){var value=entry.getAsJsonObject();long id=value.get("goodId").getAsLong();var row=repository.find(keys.get(id));
                assertEquals(Status.SIGNING,row.status());assertEquals("SENDING",row.signatureState());assertTrue(RegistrationSigningConsent.allowed(shop,row.key(),row.gtin()));
                byte[] xml=Base64.getDecoder().decode(value.get("base64Xml").getAsString());assertEquals(xml(id,false),new String(xml,StandardCharsets.UTF_8));assertArrayEquals(row.signatureXml(),xml);
                signed.add(id);
            }
            if(timeout)throw new IOException("fixture unknown batch result");if(rateLimited)throw new ZnackApiException("fixture signing deferred",429,"{}");
            if(publishOnPost)for(var id:signed)published.add(id.getAsLong());var result=new JsonObject();result.add("signed",signed);var root=new JsonObject();root.add("result",result);return root;
        }
        String xml(long id,boolean changed){return "<good id='"+id+"'>"+(changed?"changed":"reviewed")+"</good>";}
        JsonElement json(String json){return JsonParser.parseString(json);}
    }
}
