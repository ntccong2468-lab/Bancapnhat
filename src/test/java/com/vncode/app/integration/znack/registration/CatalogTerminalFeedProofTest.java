package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackModels;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogTerminalFeedProofTest {
    private static final String INN="7700000000";
    private static final String GTIN="04631993764370";
    @Test void proofRequiresTerminalFeedAndExactlyTheRequestedGtinAndGoodId()throws Exception {
        var api=new Fixture();var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Completed\",\"owner_inn\":\"7700000000\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91}]}}";
        assertTrue(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        assertFalse(service.verifiedTerminalFeed("fixture","different-feed",GTIN,91L,INN));
        api.body=api.body.replace("Completed","Processing");assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.body=api.body.replace("Processing","Completed").replace(GTIN,"04631993764363");assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.body=api.body.replace("04631993764363",GTIN).replace(":91",":92");assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Completed\",\"owner_inn\":\"7700000000\",\"item\":[{\"gtin\":\""+GTIN+"\"}]}}";assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Completed\",\"owner_inn\":\"7700000000\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":null}]}}";assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Completed\",\"owner_inn\":\"7700000000\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":0}]}}";assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Completed\",\"owner_inn\":\"7700000000\",\"item\":[{\"gtin\":\""+GTIN+"\"},{\"gtin\":\""+GTIN+"\"}]}}";assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Completed\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91},{\"gtin\":\""+GTIN+"\",\"good_id\":91}]}}";assertTrue(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
    }
    @Test void missingFeedIsNeverReplacedWithAnOlderCard() {
        for(int status:new int[]{404,429,503}){
            var api=new Fixture();api.failure=status;var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
            assertThrows(IOException.class,()->service.verifiedTerminalFeed("fixture","old-feed",GTIN,91L,INN));
            assertEquals(0,api.cardReads);
        }
    }
    @Test void exactCardMustExplicitlyBelongToParticipant()throws Exception {
        var api=new Fixture();api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Signed\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91}]}}";
        var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        assertTrue(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.owner="";assertThrows(IllegalStateException.class,()->service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.ownerIndex=true;assertTrue(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.owner="8888888888";assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
        api.owner=INN;api.body=api.body.replace("\"status\":\"Signed\"","\"status\":\"Signed\",\"owner_inn\":\"8888888888\"");
        assertFalse(service.verifiedTerminalFeed("fixture","feed",GTIN,91L,INN));
    }
    @Test void automaticRepairRequiresRawRejectedFeedAndExactOwnedCard()throws Exception {
        var api=new Fixture();api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Rejected\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91,\"status_code\":400,\"message\":\"bad field\"}]}}";
        var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        assertTrue(service.verifiedRejectedFeed("fixture","feed",GTIN,91L,INN));
        api.body=api.body.replace("Rejected","Signed");assertFalse(service.verifiedRejectedFeed("fixture","feed",GTIN,91L,INN));
        api.body=api.body.replace("Signed","Rejected");api.owner="";assertThrows(IllegalStateException.class,()->service.verifiedRejectedFeed("fixture","feed",GTIN,91L,INN));
        api.failure=404;assertThrows(IOException.class,()->service.verifiedRejectedFeed("fixture","feed",GTIN,91L,INN));
    }
    @Test void rejectionKeepsOnlyErrorsForExactIdentityAndAcceptsRepeatedRows()throws Exception {
        var api=new Fixture();var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Rejected\",\"item\":["
                +"{\"id\":\"x\",\"gtin\":\""+GTIN+"\",\"good_id\":91,\"status_code\":400,\"message\":\"first\"},"
                +"{\"id\":\"x\",\"gtin\":\""+GTIN+"\",\"good_id\":91,\"status_code\":401,\"message\":\"second\"},"
                +"{\"id\":\"y\",\"gtin\":\"04631993764363\",\"good_id\":92,\"status_code\":400,\"message\":\"foreign\"}],"
                +"\"error_details\":{\"commonError\":{\"text\":\"common\"},\"items\":[{\"id\":\"x\",\"errors\":{\"code\":\"bad\",\"text\":\"linked\"}},{\"id\":\"y\",\"errors\":{\"text\":\"foreign detail\"}}]}}}";
        var proof=service.verifiedRejectedProgress("fixture","feed",GTIN,91L,INN,()->{});
        assertNotNull(proof);String text=proof.errorMessage();assertTrue(text.contains("first"));assertTrue(text.contains("second"));assertTrue(text.contains("linked"));
        assertFalse(text.contains("foreign"));assertFalse(text.contains("common"));
        String original=api.body;api.body=original.replace("\"id\":\"x\",\"errors\"","\"id\":\"x\",\"owner_inn\":\"8888888888\",\"errors\"");
        assertNull(service.verifiedRejectedProgress("fixture","feed",GTIN,91L,INN,()->{}));api.body=original;
        api.body=api.body.replace("Rejected","Moderated");assertNull(service.verifiedRejectedProgress("fixture","feed",GTIN,91L,INN,()->{}));
        api.body=api.body.replace("Moderated","Processing");assertNull(service.verifiedRejectedProgress("fixture","feed",GTIN,91L,INN,()->{}));
    }
    @Test void multipleTerminalFeedsShareOneFreshOwnerScanAtTheEnd()throws Exception {
        var api=new Fixture();api.owner="";api.ownerIndex=true;api.dynamicFeedIds=true;
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Signed\",\"item\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91}]}}";
        var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        long proof=service.verifiedTerminalFeeds("fixture",java.util.List.of("feed-a","feed-b"),GTIN,91L,INN,()->{});
        assertNotEquals(0,proof);assertEquals(2,api.feedReads);assertEquals(1,api.indexReads);assertEquals(2,api.cardReads);
    }
    @Test void verboseItemErrorMustLinkToExactFeedItemAndNeverUseCommonError()throws Exception {
        var api=new Fixture();var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        api.body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Rejected\",\"item\":[{\"id\":\"x\",\"gtin\":\""+GTIN+"\",\"good_id\":91,\"status_code\":0}],\"error_details\":{\"commonError\":{\"text\":\"common\"},\"items\":[{\"id\":\"x\",\"errors\":{\"code\":\"e\",\"text\":\"linked-only\"}}]}}}";
        var proof=service.verifiedRejectedProgress("fixture","feed",GTIN,91L,INN,()->{});
        assertNotNull(proof);assertEquals("linked-only",proof.errorMessage());
        api.body=api.body.replace("\"id\":\"x\",\"errors\"","\"id\":\"unknown\",\"errors\"");
        assertNull(service.verifiedRejectedProgress("fixture","feed",GTIN,91L,INN,()->{}));
    }
    private static final class Fixture extends ZnackApiClient {
        String body,owner=INN;int failure,cardReads,feedReads,indexReads;boolean ownerIndex,dynamicFeedIds;
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed)throws IOException{
            feedReads++;if(failure!=0)throw new ZnackApiException("unavailable",failure,"");return JsonParser.parseString(dynamicFeedIds?body.replace("\"feed_id\":\"feed\"","\"feed_id\":\""+feed+"\""):body);
        }
        @Override public JsonElement productCards(String base,String token,String gtin){cardReads++;return JsonParser.parseString("{\"result\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91,\"owner_inn\":\""+owner+"\",\"good_status\":\"draft\",\"good_detailed_status\":[\"errors\"]}]}");}
        @Override public JsonElement catalogVersions(String base,String token,String inn,int offset){indexReads++;return JsonParser.parseString(ownerIndex?"{\"result\":{\"offset\":0,\"goods_count\":1,\"total\":1,\"last_product_number\":1,\"goods\":[{\"good_id\":91,\"etag\":\"fixture\"}]}}":"{\"result\":{\"offset\":0,\"goods_count\":0,\"total\":0,\"last_product_number\":0,\"goods\":[]}}");}
    }
}
