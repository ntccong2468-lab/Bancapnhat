package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackModels;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogOwnerEvidenceIntegrationTest {
    private static final String GTIN="04601234567893",INN="1234567890";
    @Test void ownerlessExactRejectedCardUsesFreshFilteredIndexAndRereadsCard()throws Exception{
        var api=new Fixture();var service=service(api);var guards=new AtomicInteger();
        assertTrue(service.verifiedRejectedFeed("fixture","feed",GTIN,91L,INN,guards::incrementAndGet));
        assertEquals(2,api.cardReads.get());assertEquals(1,api.indexReads.get());assertTrue(guards.get()>=4);
    }
    @Test void ownerMismatchAndCardChangedDuringIndexLookupCannotAuthorizeWrite()throws Exception{
        var wrong=new Fixture();wrong.explicitOwner="9999999999";
        assertFalse(service(wrong).verifiedRejectedFeed("fixture","feed",GTIN,91L,INN,()->{}));assertEquals(0,wrong.indexReads.get());
        var changed=new Fixture();changed.changeOnSecondRead=true;
        assertThrows(IllegalStateException.class,()->service(changed).verifiedRejectedFeed("fixture","feed",GTIN,91L,INN,()->{}));
    }
    @Test void rateLimitAndMissingIndexRemainUnknown(){
        var api=new Fixture();api.rateLimit=true;var service=service(api);
        var error=assertThrows(ZnackApiClient.ZnackApiException.class,()->service.verifiedRejectedFeed("fixture","feed",GTIN,91L,INN,()->{}));
        assertTrue(CatalogFeedback.rateLimited(error));
        var missing=new Fixture();missing.indexContainsCard=false;
        var absent=assertThrows(IllegalStateException.class,()->service(missing).verifiedRejectedFeed("fixture","feed",GTIN,91L,INN,()->{}));
        assertEquals("CATALOG_OWNER_UNCONFIRMED",absent.getMessage());
    }
    private static ZnackNationalCatalogService service(Fixture api){return new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());}
    private static final class Fixture extends ZnackApiClient {
        final AtomicInteger cardReads=new AtomicInteger(),indexReads=new AtomicInteger();
        boolean rateLimit,indexContainsCard=true,changeOnSecondRead;String explicitOwner="";
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){
            return JsonParser.parseString("{\"result\":{\"feed_id\":\""+feed+"\",\"status\":\"Rejected\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91,\"status_code\":400,\"message\":\"is_kit не допускается\"}]}}");
        }
        @Override public JsonElement productCards(String base,String token,String gtin){
            int count=cardReads.incrementAndGet();String owner=explicitOwner.isBlank()?"":",\"owner_inn\":\""+explicitOwner+"\"";
            String revision=changeOnSecondRead&&count>1?"v2":"v1";
            return JsonParser.parseString("{\"result\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91,\"good_status\":\"draft\",\"good_detailed_status\":[\"errors\"],\"_etag\":\""+revision+"\""+owner+"}]}");
        }
        @Override public JsonElement catalogVersions(String base,String token,String owner,int offset)throws IOException{
            indexReads.incrementAndGet();assertEquals(INN,owner);assertEquals(0,offset);
            if(rateLimit)throw new ZnackApiException("rate",429,"{}");
            return JsonParser.parseString("{\"apiversion\":3,\"result\":{\"goods_count\":1,\"offset\":0,\"last_product_number\":1,\"total\":1,\"goods\":[{\"good_id\":"+(indexContainsCard?91:92)+",\"etag\":\"v1\"}]}}");
        }
    }
}
