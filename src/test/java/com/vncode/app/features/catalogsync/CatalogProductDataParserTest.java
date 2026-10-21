package com.vncode.app.features.catalogsync;

import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.registration.CatalogProductDataParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class CatalogProductDataParserTest {
    @Test void actualCatalogCardLifecycleFieldsAreStoredWithoutInventingFeedOrSignatureIdentities(){
        var data=CatalogProductDataParser.parse(JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\"errors\"],\"good_signed\":false}").getAsJsonObject());
        assertEquals("draft",data.single("@catalog_status"));assertEquals(List.of("errors"),data.values("@catalog_detailed_status"));
        assertTrue(data.values("feed_id").isEmpty());assertTrue(data.values("signature").isEmpty());
    }
    @Test void actualRemoteCardIdentityIsPreservedAsLocalMetadataWithoutBorrowingOtherIds(){
        var numeric=CatalogProductDataParser.parse(JsonParser.parseString("{\"good_id\":1234567890123456789,\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}").getAsJsonObject());
        assertEquals("1234567890123456789",numeric.single("@catalog_id"));assertEquals("draft",numeric.single("@catalog_status"));assertEquals(List.of("moderation"),numeric.values("@catalog_detailed_status"));
        var string=CatalogProductDataParser.parse(JsonParser.parseString("{\"good_id\":\"000123\",\"gtin\":\"04610689132457\",\"feed_id\":\"different-id\"}").getAsJsonObject());assertEquals("000123",string.single("@catalog_id"));
        var absent=CatalogProductDataParser.parse(JsonParser.parseString("{\"gtin\":\"04610689132457\",\"feed_id\":\"different-id\",\"id\":99}").getAsJsonObject());assertTrue(absent.values("@catalog_id").isEmpty());
    }
    @Test void malformedRemoteIdsCannotBecomeTrustedCatalogIdentity(){
        for(String raw:List.of("true","{}","[]","-1","0","1.5","1e100","\" 12 \"","\"not-an-id\"")){
            var failure=assertThrows(IllegalArgumentException.class,()->CatalogProductDataParser.parse(JsonParser.parseString("{\"good_id\":"+raw+"}").getAsJsonObject()),raw);assertEquals("CATALOG_IDENTITY_INVALID",failure.getMessage());
        }
    }
    @Test void explicitCatalogSizeSystemIsPreservedWithoutConvertingTheSize(){
        var data=CatalogProductDataParser.parse(JsonParser.parseString("{\"good_attrs\":[{\"attr_id\":35,\"attr_value\":\"54\",\"attr_value_type\":\"РОССИЯ\"}]}").getAsJsonObject());
        assertEquals("54",data.single("size"));assertEquals("РОССИЯ",data.single("@size_type"));
    }
    @Test void documentedNumericIdsRestoreArticleMaterialAndFullCustomsCodeWithoutLosingConflicts(){
        var data=CatalogProductDataParser.parse(JsonParser.parseString("""
          {"tnved":"6103","good_name":"Suit","good_attrs":[
          {"attr_id":13914,"attr_value":"SUIT-01"},{"attr_id":2483,"attr_value":"100% хлопок"},
          {"attr_id":13933,"attr_value":"6103220000"},{"attr_id":35,"attr_value":"XL"},
          {"attr_id":36,"attr_value":"Черный"},{"attr_id":2504,"attr_value":"Brand"}]}
          """).getAsJsonObject());
        assertEquals("SUIT-01",data.single("article"));assertEquals("100% хлопок",data.single("material"));
        assertEquals(List.of("6103220000"),data.values("tnved"));assertEquals("XL",data.single("size"));assertEquals("Черный",data.single("color"));
        var conflict=CatalogProductDataParser.parse(JsonParser.parseString("{\"tnved\":\"6103220000\",\"good_attrs\":[{\"attr_id\":13933,\"attr_value\":\"6103230000\"}]}").getAsJsonObject());
        assertEquals(2,conflict.values("tnved").size());assertEquals("",conflict.single("tnved"));
    }
    @Test void uiCardDocumentsKeepDatesAndNeverInventExpiryOrInfiniteStatus(){
        var data=CatalogProductDataParser.parse(JsonParser.parseString("""
          {"businessLayer":{"attrGroup":[{"attributes":[
           {"attrId":2483,"attrName":"Состав","attrValue":"Хлопок"},
           {"attrId":23557,"showValue":{"number":"FIXTURE-DECLARATION","dateFrom":"06.05.2026"}},
           {"attrId":23561,"showValue":{"number":"FIXTURE-CERTIFICATE","dateFrom":"2026-01-01","dateTo":"2027-01-01","isEndless":false}}]}]}}
          """).getAsJsonObject());
        assertEquals("Хлопок",data.single("material"));assertEquals(2,data.documents().size());
        assertEquals("",data.documents().getFirst().until());assertFalse(data.documents().getFirst().endless());
        assertEquals("2027-01-01",data.documents().getLast().until());
    }
    @Test void feedDocumentLegacyFormatStaysSeparateFromOrdinaryAttributes(){
        var data=CatalogProductDataParser.parse(JsonParser.parseString("{\"good_attrs\":[{\"attr_id\":23557,\"attr_value\":\"FIXTURE-DOC:::2026-02-03\"}]}").getAsJsonObject());
        assertEquals(1,data.documents().size());assertEquals("2026-02-03",data.documents().getFirst().from());assertTrue(data.attributes().isEmpty());
    }
    @Test void uiDictionaryDisplayNamesAreUsedInsteadOfInternalCatalogValueIds(){
        var data=CatalogProductDataParser.parse(JsonParser.parseString("""
            {"businessLayer":{"attrGroup":[{"attributes":[
              {"id":2483,"name":"Состав","value":"991","valueId":991,"showValue":{"name":"Хлопок"}},
              {"id":14013,"name":"Пол","value":"992","valueId":992}
            ]}]}}
            """).getAsJsonObject());
        assertEquals("Хлопок",data.single("material"));assertTrue(data.values("gender").isEmpty());
    }
    @Test void registryKeepsOnlyActiveDocumentAndDoesNotBorrowDatesFromOtherPapers(){
        var old=new CatalogProductData(java.util.Map.of(),List.of(),List.of(
            new CatalogProductData.Document("DECLARATION","ACTIVE","2025-01-01","2027-01-01",false,"Owner",java.util.Map.of()),
            new CatalogProductData.Document("DECLARATION","SUSPENDED","2025-01-01","2030-01-01",false,"Owner",java.util.Map.of())));
        var result=CatalogProductDataParser.withRegistry(old,JsonParser.parseString("""
            {"result":{"documents":[
              {"attr_id":23557,"number":"ACTIVE","from_date":"2026-02-03","status_group":1},
              {"attr_id":23557,"number":"SUSPENDED","from_date":"2025-01-01","status_group":2},
              {"attr_id":23561,"number":"NEW","from_date":"2026-04-05","active":true}]}}
            """));
        assertEquals(List.of("ACTIVE","NEW"),result.documents().stream().map(CatalogProductData.Document::number).toList());
        assertEquals("2026-02-03",result.documents().getFirst().from());assertEquals("2027-01-01",result.documents().getFirst().until());
        assertEquals("",result.documents().getLast().until());assertFalse(result.documents().getLast().endless());
    }
    @Test void pendingRegistryAndUnrecognizedResponseCannotAuthorizeDocumentSync(){
        var pending=CatalogProductDataParser.withRegistry(CatalogProductData.EMPTY,JsonParser.parseString("""
          {"result":{"documents":[],"errors":[{"error_code":"18"}]}}
          """));
        assertEquals("REGISTRY_DOCUMENTS_PENDING",pending.single("@documents_status"));assertTrue(pending.documents().isEmpty());
        assertEquals("REGISTRY_DOCUMENTS_UNAVAILABLE",CatalogProductDataParser.withRegistry(CatalogProductData.EMPTY,JsonParser.parseString("{} ")).single("@documents_status"));
    }
    @Test void explicitSignedFlagIsPreservedAndMalformedFlagsStayUnknown(){
        assertEquals("false",CatalogProductDataParser.parse(JsonParser.parseString("{\"good_signed\":false}").getAsJsonObject()).single("@catalog_signed"));
        assertEquals("true",CatalogProductDataParser.parse(JsonParser.parseString("{\"good_signed\":true}").getAsJsonObject()).single("@catalog_signed"));
        for(String raw:List.of("\"false\"","0","{}","[]")){var failure=assertThrows(IllegalArgumentException.class,()->CatalogProductDataParser.parse(JsonParser.parseString("{\"good_signed\":"+raw+"}").getAsJsonObject()));assertEquals("CATALOG_SIGNATURE_STATUS_UNVERIFIED",failure.getMessage());}
    }

}
