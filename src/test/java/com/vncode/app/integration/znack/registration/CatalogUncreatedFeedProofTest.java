package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogUncreatedFeedProofTest {
    private static final String GTIN="04631993764370";
    private static final class Api extends ZnackApiClient {
        String body="{\"result\":{\"feed_id\":\"feed\",\"status\":\"Rejected\",\"item\":[{\"id\":\"a\",\"gtin\":\""+GTIN+"\",\"status_code\":400}]}}";
        int cardFailure;boolean cardExists;
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed)throws IOException{return JsonParser.parseString(body);}
        @Override public JsonElement productCards(String base,String token,String gtin)throws IOException {
            if(cardFailure!=0)throw new ZnackApiException("unavailable",cardFailure,"");
            return JsonParser.parseString(cardExists?"{\"result\":[{\"gtin\":\""+GTIN+"\",\"good_id\":91}]}":"{\"result\":[]}");
        }
    }
    @Test void requiresExactRejectedFeedAndSuccessfulEmptyCardRead()throws Exception {
        var api=new Api();var service=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        assertTrue(service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));
        api.cardExists=true;assertFalse(service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));api.cardExists=false;
        api.cardFailure=404;assertThrows(IOException.class,()->service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));api.cardFailure=0;
        String rejected=api.body;api.body=rejected.replace("Rejected","Processing");assertFalse(service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));
        api.body=rejected.replace("\"feed_id\":\"feed\"","\"feed_id\":\"other\"");assertFalse(service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));
        api.body=rejected.replace(GTIN,"04631993764363");assertFalse(service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));
        api.body=rejected.replace("\"status_code\":400","\"good_id\":91,\"status_code\":400");assertFalse(service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));
        api.body=rejected.replace("\"status_code\":400","\"status_code\":0");assertFalse(service.verifiedRejectedUncreatedFeed("fixture","feed",GTIN));
    }
}
