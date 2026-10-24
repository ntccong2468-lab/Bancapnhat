package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import java.io.IOException;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPreparationReuseTest {
    private final MutableClock clock=new MutableClock();
    private final FixtureApi api=new FixtureApi();
    private final ZnackNationalCatalogService catalog=service(api);
    private ZnackNationalCatalogService service(FixtureApi client){
        var auth=new ZnackAuthService(client,null){@Override public String trueApiToken(ZnackModels.Settings settings){return "fixture-token";}};
        return new ZnackNationalCatalogService(client,auth,(bytes,context)->{throw new AssertionError("No signing");},ZnackModels.Settings.empty(),clock);
    }

    @Test void oneHundredPrepareAndSendChecksReuseCategoriesButReadQuotaEveryTime()throws Exception{
        for(int i=0;i<100;i++){
            assertEquals(1,catalog.preflight("6112").categories().getFirst().id());
            assertEquals(1,catalog.preflightExisting("6112","fixture-token").categories().getFirst().id());
        }
        assertEquals(1,api.categories,"200 preparation checks need only one positive metadata read in the same session");
        assertEquals(100,api.quota,"Quota must never be memoized with category metadata");
    }
    @Test void changedTokenOrTariffCannotReuseAnotherCategoryResult()throws Exception{
        catalog.preflightExisting("6112","a");catalog.preflightExisting("6112","b");catalog.preflightExisting("6204","b");
        assertEquals(3,api.categories);
    }
    @Test void expiredOrFutureDatedCategoryReadIsReloaded()throws Exception{
        assertEquals(1,catalog.preflightExisting("6112","a").categories().getFirst().id());
        api.categoryId=2;clock.now=clock.now.plusSeconds(60);
        assertEquals(2,catalog.preflightExisting("6112","a").categories().getFirst().id());
        api.categoryId=3;clock.now=clock.now.minusSeconds(1);
        assertEquals(3,catalog.preflightExisting("6112","a").categories().getFirst().id());assertEquals(3,api.categories);
    }
    @Test void emptyOrFailedCategoryReadDoesNotHideLaterAvailableCategories()throws Exception{
        api.empty=true;assertThrows(IllegalArgumentException.class,()->catalog.preflightExisting("6112","a"));
        api.empty=false;api.fail=true;assertThrows(IOException.class,()->catalog.preflightExisting("6112","a"));
        api.fail=false;assertEquals(1,catalog.preflightExisting("6112","a").categories().getFirst().id());assertEquals(3,api.categories);
    }
    @Test void cachedCategoriesStillRequireCurrentCredentialGuard()throws Exception{
        catalog.preflightExisting("6112","a");api.invalidScope=true;
        assertThrows(SecurityException.class,()->catalog.preflightExisting("6112","a"));assertEquals(1,api.categories);
    }
    @Test void aNewSessionDoesNotInheritPositiveOrNegativeMetadata()throws Exception{
        catalog.preflightExisting("6112","a");api.categoryId=2;
        assertEquals(2,service(api).preflightExisting("6112","a").categories().getFirst().id());assertEquals(2,api.categories);
    }
    @Test void allocationChecksFreshQuotaAfterAnEarlierSuccessfulPreparation()throws Exception{
        catalog.preflight("6112");api.exhausted=true;
        assertThrows(IllegalStateException.class,()->catalog.generateOne("fixture-token",Set.of()));
        assertEquals(2,api.quota);assertEquals(0,api.allocations);
    }
    @Test void exhaustedQuotaStillAllowsAnUnclaimedPreviouslyAllocatedDraft()throws Exception{
        api.exhausted=true;api.existingDraft=true;
        assertEquals("04631993764370",catalog.generateOne("fixture-token",Set.of()));assertEquals(1,api.quota);assertEquals(0,api.allocations);
    }
    private static final class MutableClock extends Clock{
        Instant now=Instant.parse("2026-10-24T10:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    private static final class FixtureApi extends ZnackApiClient{
        int categories,quota,allocations,categoryId=1;boolean empty,fail,invalidScope,exhausted,existingDraft;
        @Override public void assertCatalogReadScope(){if(invalidScope)throw new SecurityException("fixture credential changed");}
        @Override public JsonElement nationalCatalogCategories(String base,String token,String tnved)throws IOException{
            categories++;if(fail)throw new IOException("fixture read failed");
            return JsonParser.parseString(empty?"{\"result\":[]}":"{\"result\":[{\"cat_id\":"+categoryId+",\"cat_name\":\"Clothes\",\"category_active\":true}]}");
        }
        @Override public JsonElement generatedGtins(String base,String token){
            quota++;return JsonParser.parseString("{\"result\":{\"monthly-limit\":{\"limit\":1000,\"usage\":"+(exhausted?1000:0)+"},\"drafts\":["+(existingDraft?"{\"gtin\":\"04631993764370\"}":"")+"]}}");
        }
        @Override public JsonElement generateGtins(String base,String token,int quantity){allocations++;throw new AssertionError("No new GTIN should be allocated");}
    }
}
