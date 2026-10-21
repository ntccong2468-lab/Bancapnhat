package com.vncode.app.integration.znack.registration.v3;
import com.google.gson.Gson;
import com.vncode.app.integration.wb.WbProductCardsResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WbRegistrationImportTest {
    private WbProductCardsResponse page(int total,long nm,long variant,String cursor){
        String card="{\"nmID\":"+nm+",\"subjectID\":1,\"vendorCode\":\"ART\",\"title\":\"Áo\",\"sizes\":[{\"chrtID\":"+variant+",\"techSize\":\"M\",\"wbSize\":\"46\",\"skus\":[\"one\",\"two\"]},{\"chrtID\":"+(variant+1)+",\"techSize\":\"L\",\"wbSize\":\"48\",\"skus\":[\"three\"]}]}";
        return new Gson().fromJson("{\"cards\":["+String.join(",",Collections.nCopies(total,card))+"],\"cursor\":{\"total\":"+total+",\"nmID\":"+nm+",\"updatedAt\":\""+cursor+"\"}}",WbProductCardsResponse.class);
    }
    @Test void keepsAllSizesAndBarcodesWithoutCreatingAnyRegistration() throws Exception {
        var result=WbRegistrationImport.read((u,n)->page(1,10,20,"next"));
        assertEquals(2,result.size());assertEquals(List.of("one","two"),result.getFirst().barcodes());assertEquals("48",result.get(1).russianSize());
    }
    @Test void followsCursorAcrossAFullPageAndPreservesTheSubjectName()throws Exception {
        var cards=new com.google.gson.JsonArray();
        for(int i=0;i<100;i++){
            var card=new Gson().toJsonTree(page(1,1000+i,20000+i*2,"unused").getCards().getFirst()).getAsJsonObject();card.addProperty("subjectName","Trang phục");cards.add(card);
        }
        var first=new com.google.gson.JsonObject();first.add("cards",cards);first.add("cursor",com.google.gson.JsonParser.parseString("{\"total\":100,\"nmID\":1099,\"updatedAt\":\"2026-10-21T00:00:00Z\"}"));
        var response=new Gson().fromJson(first,WbProductCardsResponse.class);
        var result=WbRegistrationImport.read((updated,nm)->{
            if(nm==null)return response;
            if(nm!=1099||!"2026-10-21T00:00:00Z".equals(updated))throw new IllegalStateException("bad cursor");
            return page(1,9999,88881,"tail");
        });
        assertEquals(202,result.size());assertEquals("Trang phục",result.getFirst().subjectName());assertEquals(9999,result.get(200).nmId());
    }
    @Test void duplicateCardsAreNotSilentlyAccepted(){
        assertThrows(IllegalStateException.class,()->WbRegistrationImport.read((u,n)->page(2,10,20,"next")));
    }
    @Test void rejectsMalformedCursorInsteadOfTreatingItAsEmptyCatalog(){
        var response=new Gson().fromJson("{\"cards\":[]}",WbProductCardsResponse.class);
        assertThrows(IllegalStateException.class,()->WbRegistrationImport.read((u,n)->response));
    }
}
