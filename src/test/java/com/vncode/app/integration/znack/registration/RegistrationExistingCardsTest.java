package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.registration.RegistrationExistingCards.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.CatalogSession;

class RegistrationExistingCardsTest {
    @TempDir Path directory;
    Shop shop;CatalogSession session;String previous;
    final AtomicInteger loads=new AtomicInteger(),observations=new AtomicInteger();
    final AtomicReference<String> scope=new AtomicReference<>("fixture-scope");
    final AtomicReference<List<RegisteredGtin>> rows=new AtomicReference<>(List.of());
    @BeforeEach void setup()throws Exception{
        previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        session=new CatalogSession("fixture",new ZnackNationalCatalogService(new ZnackApiClient(),null,null,ZnackModels.Settings.empty()));
    }
    @AfterEach void cleanup(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    LookupContext context(){return new LookupContext((s,c)->{loads.incrementAndGet();return rows.get();},(c,g,id)->{observations.incrementAndGet();var candidate=rows.get().stream().filter(r->RegisteredGtinProof.sameCode(r.gtin(),g)).findFirst().orElseThrow();return observed(candidate,candidate.published()?"Signed":"CardDraft");},(s,c)->scope.get());}
    Variant variant(String article,String color,String size,List<String> codes){return new Variant(new ProductKey(shop.getId(),shop.getMarketplace(),"101","201"),article,color,size,"","6203420000",codes,"Брюки","Брюки","Fixture");}
    RegisteredGtin card(String gtin,String id,String article,String color,String size,boolean published,boolean unit){
        var raw=JsonParser.parseString("{\"good_id\":\""+id+"\",\"good_status\":\""+(published?"published":"draft")+"\",\"good_detailed_status\":[\"draft\"],\"article\":\""+article+"\",\"color\":\""+color+"\",\"size\":\""+size+"\",\"tnved\":\"6203420000\"}").getAsJsonObject();
        return new RegisteredGtin(gtin,article,color,size,unit,published,"National Catalog@fixture",Instant.now(),CatalogProductDataParser.parse(raw));
    }
    ZnackNationalCatalogService.ObservedCard observed(RegisteredGtin card,String progress){return new ZnackNationalCatalogService.ObservedCard(Long.parseLong(card.details().single("@catalog_id")),card.gtin(),card.published()?"published":"draft",new ZnackNationalCatalogService.FeedProgress(progress,Long.parseLong(card.details().single("@catalog_id")),List.of()),card.details(),Instant.now());}
    @Test void anApprovedBatchLoadsOneCompleteSnapshotAcrossWorkerRowsAndANewOperationReadsAgain()throws Exception{
        rows.set(List.of(card("00000000000017","41","OTHER","Brown","52",true,true)));
        try(var context=context()){
            for(int n=0;n<100;n++)assertTrue(context.lookup(shop,variant("NEW-"+n,"Brown","52",List.of()),session).isEmpty());
            assertEquals(1,loads.get());assertEquals(0,observations.get());
            var worker=new java.util.concurrent.FutureTask<>(()->context.lookup(shop,variant("ANOTHER","Brown","52",List.of()),session));var thread=new Thread(worker);thread.start();assertTrue(worker.get(5,java.util.concurrent.TimeUnit.SECONDS).isEmpty());thread.join();assertEquals(1,loads.get());
        }
        try(var next=context()){assertTrue(next.lookup(shop,variant("NEW","Brown","52",List.of()),session).isEmpty());}assertEquals(2,loads.get());
    }
    @Test void existingDraftAndRejectedCardAreFoundBeforeAnyNewAllocationWithoutInventingAFeed()throws Exception{
        var draft=card("00000000000017","00041","PANTS-1","Brown","52",false,true);rows.set(List.of(draft));
        try(var context=context()){var found=context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session).orElseThrow();assertEquals(41,found.goodId());assertEquals(draft.gtin(),found.gtin());assertFalse(found.progress().signed());assertEquals("draft",found.rawStatus());assertEquals("00041",found.details().single("@catalog_id"));}
        var rejected=new LookupContext((s,c)->rows.get(),(c,g,id)->new ZnackNationalCatalogService.ObservedCard(41,g,"draft",new ZnackNationalCatalogService.FeedProgress("Error",41L,List.of("Existing attribute error")),draft.details(),Instant.now()),(s,c)->scope.get());
        try(rejected){var found=rejected.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session).orElseThrow();assertTrue(found.progress().failed());assertEquals(41,found.goodId());}
    }
    @Test void equivalentGtinRepresentationsPreserveOneActualIdentityAndLeadingZeros()throws Exception{
        var first=card("04645588781154","41","PANTS-1","Brown","52",true,true);var same=card("4645588781154","41","PANTS-1","Brown","52",true,true);rows.set(List.of(first,same));
        try(var context=context()){var found=context.lookup(shop,variant("PANTS-1","Brown","52",List.of("4645588781154")),session).orElseThrow();assertEquals("04645588781154",found.gtin());assertEquals(1,observations.get());}
    }
    @Test void contradictingBarcodesDifferentSizesAndNonTradeUnitCardsBlockInsteadOfClaimingNoMatch(){
        for(var existing:List.of(card("00000000000017","41","OTHER","Brown","52",true,true),card("00000000000017","41","PANTS-1","Brown","54",true,true),card("00000000000017","41","PANTS-1","Brown","52",false,false))){
            rows.set(List.of(existing));try(var context=context()){assertEquals("CATALOG_EXISTING_CARD_VARIANT_CONFLICT",assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of(existing.gtin())),session)).getMessage());}
        }assertEquals(0,observations.get());
        rows.set(List.of(card("00000000000017","41","PANTS-1","Brown","52",false,false)));try(var context=context()){assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session));}assertEquals(0,observations.get());
    }
    @Test void ambiguousExistingGtinsCannotSelectAnArbitraryCard(){
        rows.set(List.of(card("00000000000017","41","PANTS-1","Brown","52",true,true),card("00000000000024","42","PANTS-1","Brown","52",false,true)));
        try(var context=context()){assertEquals("CATALOG_EXISTING_CARD_AMBIGUOUS",assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session)).getMessage());}assertEquals(0,observations.get());
    }
    @Test void aCompleteReadFailurePoisonsOnlyThisOperationAndANewOperationCanRetry()throws Exception{
        var failure=new java.net.SocketTimeoutException("fixture incomplete scan");var calls=new AtomicInteger();
        try(var context=new LookupContext((s,c)->{calls.incrementAndGet();throw failure;},(c,g,id)->{fail("Unknown lookup must not read or create a card");return null;},(s,c)->scope.get())){
            assertSame(failure,assertThrows(java.net.SocketTimeoutException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session)));assertSame(failure,assertThrows(java.net.SocketTimeoutException.class,()->context.lookup(shop,variant("OTHER","Brown","52",List.of()),session)));assertEquals(1,calls.get());
        }
        try(var recovered=context()){assertTrue(recovered.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session).isEmpty());}
    }
    @Test void deterministicRowErrorsKeepTheCompleteSnapshotUsableForOtherApprovedVariants()throws Exception{
        for(String issue:List.of("conflict","ambiguous","tnved")){
            var bad=card("00000000000017","41","PANTS-1","Brown","52",true,true);var good=card("00000000000024","42","OTHER","Black","54",true,true);var snapshot=new ArrayList<>(List.of(bad,good));if(issue.equals("ambiguous"))snapshot.add(card("00000000000031","43","PANTS-1","Brown","52",false,true));var sourceReads=new AtomicInteger();
            try(var context=new LookupContext((s,c)->{sourceReads.incrementAndGet();return snapshot;},(c,g,id)->{
                var actual=snapshot.stream().filter(row->RegisteredGtinProof.sameCode(row.gtin(),g)).findFirst().orElseThrow();
                if(issue.equals("tnved")&&actual.gtin().equals(bad.gtin())){var fields=new LinkedHashMap<>(actual.details().fields());fields.put("tnved",List.of("6203430000"));return new ZnackNationalCatalogService.ObservedCard(41,g,"published",new ZnackNationalCatalogService.FeedProgress("Signed",41L,List.of()),new com.vncode.app.features.catalogsync.CatalogProductData(fields,actual.details().attributes(),actual.details().documents()),Instant.now());}
                return observed(actual,actual.published()?"Signed":"CardDraft");
            },(s,c)->scope.get())){
                var failing=variant(issue.equals("conflict")?"DIFFERENT":"PANTS-1","Brown","52",issue.equals("conflict")?List.of(bad.gtin()):List.of());assertThrows(RegistrationValidationException.class,()->context.lookup(shop,failing,session),issue);
                var independent=new Variant(new ProductKey(shop.getId(),shop.getMarketplace(),"102","202"),"OTHER","Black","54","","6203420000",List.of(),"Брюки","Брюки","Fixture");var found=context.lookup(shop,independent,session).orElseThrow();assertEquals(good.gtin(),found.gtin(),issue);assertEquals(42,found.goodId());assertEquals(1,sourceReads.get(),"A per-row issue must not reload or discard a complete snapshot");
            }
        }
    }
    @Test void snapshotErrorsAndAuthenticationFailuresStillPoisonTheReadOperation(){
        var disguisedSourceFailure=new RegistrationValidationException("CATALOG_EXISTING_CARD_AMBIGUOUS");var sourceReads=new AtomicInteger();
        try(var context=new LookupContext((s,c)->{sourceReads.incrementAndGet();throw disguisedSourceFailure;},(c,g,id)->{fail("A failed snapshot must not continue");return null;},(s,c)->scope.get())){
            assertSame(disguisedSourceFailure,assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session)));assertSame(disguisedSourceFailure,assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("OTHER","Black","54",List.of()),session)));assertEquals(1,sourceReads.get());
        }
        var bad=card("00000000000017","41","PANTS-1","Brown","52",true,true);var good=card("00000000000024","42","OTHER","Black","54",true,true);var denied=new ZnackApiClient.ZnackApiException("fixture",401,"{}");var exactReads=new AtomicInteger();
        try(var context=new LookupContext((s,c)->List.of(bad,good),(c,g,id)->{exactReads.incrementAndGet();throw denied;},(s,c)->scope.get())){
            assertSame(denied,assertThrows(ZnackApiClient.ZnackApiException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session)));assertSame(denied,assertThrows(ZnackApiClient.ZnackApiException.class,()->context.lookup(shop,variant("OTHER","Black","54",List.of()),session)));assertEquals(1,exactReads.get());
        }
    }
    @Test void freshReadbackFailureOrDisappearanceNeverFallsBackToTheEarlierSnapshot()throws Exception{
        rows.set(List.of(card("00000000000017","41","PANTS-1","Brown","52",true,true)));var failure=new IOException("fixture exact readback failed");var reads=new AtomicInteger();
        try(var context=new LookupContext((s,c)->rows.get(),(c,g,id)->{reads.incrementAndGet();throw failure;},(s,c)->scope.get())){assertSame(failure,assertThrows(IOException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session)));assertSame(failure,assertThrows(IOException.class,()->context.lookup(shop,variant("OTHER","Brown","52",List.of()),session)));assertEquals(1,reads.get());}
        try(var context=new LookupContext((s,c)->rows.get(),(c,g,id)->null,(s,c)->scope.get())){assertEquals("CATALOG_EXISTING_CARD_READBACK_REQUIRED",assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session)).getMessage());}
    }
    @Test void changedOwnerProfileOrOriginalSessionCannotReuseEvenAnEmptyNegativeSnapshot()throws Exception{
        try(var context=context()){assertTrue(context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session).isEmpty());scope.set("different-account");assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),new CatalogSession("new-client",session.catalog())));assertEquals(1,loads.get());}
        scope.set("fixture-scope");try(var context=context()){assertTrue(context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session).isEmpty());System.setProperty("vncode.appdata.dir",directory.resolve("different-profile").toString());try{assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session));}finally{System.setProperty("vncode.appdata.dir",directory.toString());}}
    }
    @Test void missingVariantFactsOrFreshTariffChangesRequireReviewInsteadOfCreatingAnotherCard(){
        rows.set(List.of(card("00000000000017","41","PANTS-1","Brown","52",true,true)));try(var context=context()){assertEquals("CATALOG_EXISTING_CARD_VARIANT_UNVERIFIED",assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("PANTS-1","","52",List.of("00000000000017")),session)).getMessage());}
        var old=rows.get().getFirst();var fields=new LinkedHashMap<>(old.details().fields());fields.put("tnved",List.of("6203430000"));var changed=new com.vncode.app.features.catalogsync.CatalogProductData(fields,old.details().attributes(),old.details().documents());
        try(var context=new LookupContext((s,c)->rows.get(),(c,g,id)->new ZnackNationalCatalogService.ObservedCard(41,g,"published",new ZnackNationalCatalogService.FeedProgress("Signed",41L,List.of()),changed,Instant.now()),(s,c)->scope.get())){assertEquals("CATALOG_REGISTERED_TNVED_MISMATCH",assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("PANTS-1","Brown","52",List.of()),session)).getMessage());}
    }
    @Test void explicitRussianSizeAndEstablishedOzonSkuRuleAreCheckedWithoutGuessingAConversion()throws Exception{
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);var source=card("00000000000017","41","152 nau dashoa","Коричневый","52",true,true);var fields=new LinkedHashMap<>(source.details().fields());fields.put("@size_type",List.of("РОССИЯ"));var typed=new RegisteredGtin(source.gtin(),source.article(),source.color(),source.size(),true,true,source.source(),source.syncedAt(),new com.vncode.app.features.catalogsync.CatalogProductData(fields,source.details().attributes(),source.details().documents()));rows.set(List.of(typed));
        var variant=new Variant(new ProductKey(ozon.getId(),Marketplace.OZON,"501","152DASH-52"),"152DASH-52","Brown","XL","52","6203420000",List.of(),"Костюм спортивный","Костюмы","");try(var context=context()){assertTrue(context.lookup(ozon,variant,session).isPresent());}
        var noSizeMapping=new Variant(variant.key(),variant.article(),variant.color(),"XL","","6203420000",List.of(typed.gtin()),variant.title(),variant.category(),variant.brand());try(var context=context()){assertThrows(RegistrationValidationException.class,()->context.lookup(ozon,noSizeMapping,session));}
    }
    @Test void aRestartedRegistrationLookupReadsPageZeroEvenIfTheUiScanHasASavedCursor()throws Exception{
        String source="National Catalog@fixture";var stored=card("00000000000017","41","OLD","Brown","52",true,true);new CatalogScanCheckpoint(shop.getId(),source).load();new CatalogScanCheckpoint(shop.getId(),source).save(new CatalogScanCheckpoint.State(1,2,false,List.of(stored.gtin()),0,List.of(),List.of()));var seen=new ArrayList<Integer>();
        try(var context=new LookupContext((s,c)->new NationalCatalogGtinSource(s.getId(),page->{seen.add(page);return JsonParser.parseString("{\"results\":[],\"total\":0}");},g->{fail("Old UI cursor cannot become a registration card proof");return null;},source).read(s.getId()),(c,g,id)->{fail("Fresh complete absence has no candidate");return null;},(s,c)->scope.get())){assertTrue(context.lookup(shop,variant("NEW","Brown","52",List.of()),session).isEmpty());}assertEquals(List.of(0),seen);
        assertNotNull(new CatalogScanCheckpoint(shop.getId(),source).load(),"Registration must not erase the independent resumable UI scan");
    }
    @Test void closingAReadOperationPreventsItFromBecomingALongLivedNegativeCache()throws Exception{
        var context=context();assertTrue(context.lookup(shop,variant("NEW","Brown","52",List.of()),session).isEmpty());context.close();assertThrows(RegistrationValidationException.class,()->context.lookup(shop,variant("NEW","Brown","52",List.of()),session));assertEquals(1,loads.get());
    }
}
