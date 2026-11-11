package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonProductSnapshotReader;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Malformed remote document reads must not erase a previously observed owned certificate. */
class OzonDocumentReadbackIntegrityTest {
    @TempDir Path root;
    final Shop shop=new Shop(1,"Fixture",Marketplace.OZON,"fixture-client","fixture");
    final ProductKey key=new ProductKey(1,Marketplace.OZON,"321","O-1");
    final Map<Integer,JsonObject> pages=new HashMap<>();
    OzonProductSnapshotReader reader;

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','fixture-client','fixture')");
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,archived,synced_at) VALUES(1,'321','O-1','99','Fixture',0,'now')");
        }
        reader=new OzonProductSnapshotReader(s->(path,body,mutation)->{
            assertFalse(mutation);
            return switch(path){
                case "v3/product/info/list"->JsonParser.parseString("{\"items\":[{\"id\":321,\"sku\":99,\"offer_id\":\"O-1\",\"name\":\"Fixture\"}]}");
                case "v4/product/info/attributes"->JsonParser.parseString("{\"result\":[{\"id\":321,\"offer_id\":\"O-1\",\"name\":\"Fixture\",\"description_category_id\":12,\"type_id\":13,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"attributes\":[]}]}");
                case "v1/description-category/attribute"->JsonParser.parseString("{\"result\":[]}");
                case "v1/product/certificate/list"->{
                    assertEquals("O-1",body.getAsJsonObject().get("offer_id").getAsString());
                    var response=pages.get(body.getAsJsonObject().get("page").getAsInt());
                    if(response==null)throw new AssertionError("Unexpected certificate page");
                    yield response.deepCopy();
                }
                default->throw new AssertionError("Unexpected route "+path);
            };
        });
        pages.put(1,page(array(certificate(77)),new JsonPrimitive(1)));
        assertEquals(DocumentState.VALID,reader.readForSynchronization(shop,key).documents().getFirst().state());
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @ParameterizedTest @ValueSource(strings={"null","{}","true","\"not-an-array\""})
    void malformedCertificateCollectionCannotEraseThePreviousVerifiedSnapshot(String raw)throws Exception{
        pages.put(1,page(JsonParser.parseString(raw),new JsonPrimitive(1)));
        assertRejectedAndRetained();
    }

    @ParameterizedTest @ValueSource(strings={"null","true","\"not-an-object\"","[]"})
    void nonObjectCertificateCannotAbortRefreshOutsideItsRecoverableReadFailure(String raw)throws Exception{
        pages.put(1,page(array(JsonParser.parseString(raw)),new JsonPrimitive(1)));
        assertRejectedAndRetained();
    }

    @ParameterizedTest @ValueSource(strings={"1.5","-1","11","null","\"1\"","true","{}"})
    void malformedOrOutOfRangePageCountCannotTruncateAndReplaceTheSavedDocumentSet(String raw)throws Exception{
        pages.put(1,page(array(certificate(88)),JsonParser.parseString(raw)));
        assertRejectedAndRetained();
    }

    @Test void zeroPageCountWithCertificatesCannotBeTreatedAsOneSuccessfulPage()throws Exception{
        pages.put(1,page(array(certificate(88)),new JsonPrimitive(0)));
        assertRejectedAndRetained();
    }

    @Test void repeatedCertificateIdsWithinOnePageCannotReplaceThePreviousDocumentSet()throws Exception{
        pages.put(1,page(array(certificate(88),certificate(88)),new JsonPrimitive(1)));
        assertRejectedAndRetained();
    }

    @Test void repeatedCertificateIdsAcrossPagesCannotBeMergedAsACompleteObservation()throws Exception{
        pages.put(1,page(array(certificate(88)),new JsonPrimitive(2)));
        pages.put(2,page(array(certificate(88)),new JsonPrimitive(2)));
        assertRejectedAndRetained();
    }

    @Test void changingPageCountCannotMakeAnIncompleteObservationReplaceThePreviousSet()throws Exception{
        pages.put(1,page(array(certificate(88)),new JsonPrimitive(2)));
        pages.put(2,page(array(certificate(89)),new JsonPrimitive(1)));
        assertRejectedAndRetained();
    }

    @Test void contradictoryDocumentDatesAreARecoverableReadFailureAndKeepThePreviousSnapshot()throws Exception{
        var document=certificate(88);document.addProperty("issue_date",LocalDate.now().plusYears(2)+"T00:00:00Z");
        pages.put(1,page(array(document),new JsonPrimitive(1)));
        assertRejectedAndRetained();
    }

    @Test void explicitEmptyZeroPageCountIsAnAuthoritativeEmptyDocumentSet()throws Exception{
        pages.put(1,page(new JsonArray(),new JsonPrimitive(0)));
        assertTrue(reader.readForSynchronization(shop,key).documents().isEmpty());
        assertTrue(new ProductRepository().localSnapshot(key).orElseThrow().documents().isEmpty());
    }

    @Test void missingOptionalPageCountKeepsTheLegacySinglePageContract()throws Exception{
        var result=page(array(certificate(88)),new JsonPrimitive(1));result.getAsJsonObject("result").remove("page_count");pages.put(1,result);
        assertEquals("88",reader.readForSynchronization(shop,key).documents().getFirst().id());
    }

    @ParameterizedTest @ValueSource(strings={"issue_date","expire_date"})
    void validDatePrefixWithGarbageSuffixCannotGrantApproval(String field)throws Exception{
        var document=certificate(88);document.addProperty(field,(field.equals("issue_date")?LocalDate.now().minusDays(1):LocalDate.now().plusYears(1))+"unexpected");
        pages.put(1,page(array(document),new JsonPrimitive(1)));
        assertEquals(DocumentState.UNVERIFIED,reader.readForSynchronization(shop,key).documents().getFirst().state());
    }

    @ParameterizedTest @ValueSource(strings={"issue_date","expire_date"})
    void validDateTimePrefixWithGarbageSuffixCannotGrantApproval(String field)throws Exception{
        var document=certificate(88);document.addProperty(field,(field.equals("issue_date")?LocalDate.now().minusDays(1):LocalDate.now().plusYears(1))+"T00:00:00Zunexpected");
        pages.put(1,page(array(document),new JsonPrimitive(1)));
        assertEquals(DocumentState.UNVERIFIED,reader.readForSynchronization(shop,key).documents().getFirst().state());
    }

    @ParameterizedTest @ValueSource(strings={"","T00:00:00Z","T00:00:00.123Z","T03:00:00+03:00","T00:00:00"})
    void completeIsoDateAndDateTimeFormatsRemainValid(String suffix)throws Exception{
        var document=certificate(88);document.addProperty("issue_date",LocalDate.now().minusDays(1)+suffix);document.addProperty("expire_date",LocalDate.now().plusYears(1)+suffix);
        pages.put(1,page(array(document),new JsonPrimitive(1)));
        var read=reader.readForSynchronization(shop,key);assertEquals(DocumentState.VALID,read.documents().getFirst().state());
        assertEquals(LocalDate.now().minusDays(1),read.documents().getFirst().validFrom());assertEquals(LocalDate.now().plusYears(1),read.documents().getFirst().validUntil());
    }

    private void assertRejectedAndRetained()throws Exception{
        var repository=new ProductRepository();var before=repository.localSnapshot(key).orElseThrow();
        assertThrows(IOException.class,()->reader.readForSynchronization(shop,key));
        var after=repository.localSnapshot(key).orElseThrow();assertEquals(before,after);assertEquals("77",after.documents().getFirst().id());assertEquals(DocumentState.VALID,after.documents().getFirst().state());
    }
    private JsonObject certificate(int id){
        var value=new JsonObject();value.addProperty("certificate_id",id);value.addProperty("certificate_number","SYNTHETIC-DOC-"+id);value.addProperty("type_code","declaration");value.addProperty("status_code","approved");value.addProperty("products_count",1);value.addProperty("issue_date",LocalDate.now().minusDays(1)+"T00:00:00Z");value.addProperty("expire_date",LocalDate.now().plusYears(1)+"T00:00:00Z");return value;
    }
    private JsonArray array(JsonElement... values){var result=new JsonArray();for(var value:values)result.add(value);return result;}
    private JsonObject page(JsonElement certificates,JsonElement pageCount){var result=new JsonObject();result.add("certificates",certificates);result.add("page_count",pageCount);var response=new JsonObject();response.add("result",result);return response;}
}
