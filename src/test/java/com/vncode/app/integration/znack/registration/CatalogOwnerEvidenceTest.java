package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogOwnerEvidenceTest {
    private static JsonObject page(int total,int next,String... values){
        var rows=new JsonArray();
        for(String value:values){var parts=value.split(":",2);var row=new JsonObject();row.addProperty("good_id",parts[0]);row.addProperty("etag",parts[1]);rows.add(row);}
        var result=new JsonObject();result.addProperty("total",total);result.addProperty("last_product_number",next);result.addProperty("offset",next-values.length);result.addProperty("goods_count",values.length);result.add("goods",rows);
        var root=new JsonObject();root.addProperty("apiversion",3);root.add("result",result);return root;
    }
    @Test void exactPositiveMatchIsReadFreshUnderTheRequestedOwner()throws Exception{
        var calls=new AtomicInteger();var guards=new AtomicInteger();
        assertTrue(CatalogOwnerEvidence.verify("1234567890",91,"v2",(owner,offset)->{
            assertEquals("1234567890",owner);calls.incrementAndGet();return offset==0?page(2,1,"90:v1"):page(2,2,"91:v2");
        },guards::incrementAndGet));
        assertEquals(2,calls.get());assertEquals(5,guards.get());
    }
    @Test void noMatchMismatchAndMalformedOrChangingPageAreUnknown(){
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->page(1,1,"90:v1"),()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"v2",(o,i)->page(1,1,"91:v1"),()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->page(2,1,"91:v1","92:v1"),()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->i==0?page(2,1,"90:v1"):page(3,2,"91:v1"),()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->i==0?page(2,1,"90:v1"):page(2,2,"90:v2"),()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->{var r=page(1,1,"91:v1");r.getAsJsonObject("result").remove("offset");return r;},()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->{var r=page(1,1,"91:v1");r.getAsJsonObject("result").remove("goods_count");return r;},()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->{var r=page(1,1,"91:v1");r.addProperty("error","failed");return r;},()->{}));
        assertThrows(IllegalStateException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->{var r=page(1,1,"91:v1");r.addProperty("apiversion",4);return r;},()->{}));
    }
    @Test void rateLimitsNetworkAndScopeChangesAreNeverTreatedAsAbsence(){
        var limited=new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("rate",429,"{}");
        assertSame(limited,assertThrows(com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException.class,
                ()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->{throw limited;},()->{})));
        assertThrows(IOException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->{throw new IOException("network");},()->{}));
        var guards=new AtomicInteger();assertThrows(SecurityException.class,()->CatalogOwnerEvidence.verify("1234567890",91,"",(o,i)->page(1,1,"91:v1"),()->{if(guards.incrementAndGet()==2)throw new SecurityException("scope changed");}));
    }
}
