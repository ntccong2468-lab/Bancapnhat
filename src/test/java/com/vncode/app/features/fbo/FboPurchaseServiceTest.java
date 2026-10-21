package com.vncode.app.features.fbo;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FboPurchaseServiceTest {
    @Test void eachConfirmedIntentUsesCanonicalStableDistinctPipelineUuids(){
        String intent=UUID.randomUUID().toString();String gtin="00000000000017";
        String key=FboPurchaseService.requestKey(intent,gtin);
        assertEquals(key,UUID.fromString(key).toString());
        assertEquals(key,FboPurchaseService.requestKey(intent,gtin));
        assertEquals(key,FboPurchaseService.requestKey(intent,"000000000017"));
        assertNotEquals(key,FboPurchaseService.requestKey(intent,"00000000000024"));
        assertNotEquals(key,FboPurchaseService.requestKey(UUID.randomUUID().toString(),gtin));
    }
}
