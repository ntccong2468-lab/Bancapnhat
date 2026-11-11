package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonProductSnapshotReader;
import com.vncode.app.integration.wb.WbProductSnapshotReader;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable marketplace readbacks; no seller API or registry mutation. */
class ProductDocumentVerificationReadbackTest {
    @TempDir Path root;
    final Shop ozon=new Shop(1,"Fixture",Marketplace.OZON,"fixture-client","fixture");
    final Shop wb=new Shop(2,"Fixture",Marketplace.WILDBERRIES,null,"fixture");
    final ProductKey ozonKey=new ProductKey(1,Marketplace.OZON,"321","O-1");
    final ProductKey wbKey=new ProductKey(2,Marketplace.WILDBERRIES,"123","101");

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','fixture-client','fixture'),(2,'Fixture','WILDBERRIES',NULL,'fixture')");
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,archived,synced_at) VALUES(1,'321','O-1','99','Fixture',0,'now')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(2,123,'Fixture','now')");
        }
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @ParameterizedTest @ValueSource(strings={"","unverified","unknown_future_status","APPROVED_WITHOUT_EVIDENCE"})
    void unknownOzonApprovalIsNeverInferredFromDatesOrDocumentNumber(String status)throws Exception{
        assertEquals(DocumentState.UNVERIFIED,readOzon(status,LocalDate.now().minusDays(1),LocalDate.now().plusYears(1)).documents().getFirst().state());
        assertEquals(DocumentState.UNVERIFIED,row(ozon).documentState());
    }

    @Test void expiredDatesOverrideOzonApproval()throws Exception{
        assertEquals(DocumentState.EXPIRED,readOzon("approved",LocalDate.now().minusYears(2),LocalDate.now().minusDays(1)).documents().getFirst().state());
        assertEquals(DocumentState.EXPIRED,row(ozon).documentState());
    }

    @ParameterizedTest @CsvSource({"approved,VALID","awaiting_verification,PENDING","verification,PENDING","declined,REJECTED"})
    void ozonDocumentedStatusIsPreservedThroughSnapshotAndProjection(String status,DocumentState expected)throws Exception{
        var result=readOzon(status,LocalDate.now().minusDays(1),LocalDate.now().plusYears(1));
        assertEquals(expected,result.documents().getFirst().state());
        assertEquals(status,result.documents().getFirst().metadata().get("status_code"));
        assertEquals("https://api-seller.ozon.ru/v1/product/certificate/list",result.documents().getFirst().source());
        assertNotNull(result.documents().getFirst().checkedAt());
        assertEquals(expected,row(ozon).documentState());
        assertEquals(1,new ProductRepository().loadPage(query(ozon,expected)).total());
    }

    @Test void approvedOzonDocumentNotYetEffectiveRemainsPending()throws Exception{
        assertEquals(DocumentState.PENDING,readOzon("approved",LocalDate.now().plusDays(1),LocalDate.now().plusYears(1)).documents().getFirst().state());
        assertEquals(DocumentState.PENDING,row(ozon).documentState());
    }

    @ParameterizedTest @ValueSource(strings={"true","1","{}","[]"})
    void ozonMalformedStatusTypesCannotBeInterpretedAsApproval(String raw)throws Exception{
        assertEquals(DocumentState.UNVERIFIED,readOzon("approved",LocalDate.now().minusDays(1),LocalDate.now().plusYears(1),doc->doc.add("status_code",JsonParser.parseString(raw))).documents().getFirst().state());
        assertEquals(DocumentState.UNVERIFIED,row(ozon).documentState());
    }

    @ParameterizedTest @ValueSource(strings={"issue_date","expire_date"})
    void ozonMalformedExplicitDateCannotProveCurrentValidity(String field)throws Exception{
        assertEquals(DocumentState.UNVERIFIED,readOzon("approved",LocalDate.now().minusDays(1),LocalDate.now().plusYears(1),doc->doc.addProperty(field,"invalid-date")).documents().getFirst().state());
        assertEquals(DocumentState.UNVERIFIED,row(ozon).documentState());
    }

    @ParameterizedTest @ValueSource(strings={"0","-1","1.5","true","{}","[]","\"1\"","null"})
    void ozonExplicitUnboundOrMalformedProductCountCannotProveApprovedAttachment(String raw)throws Exception{
        assertEquals(DocumentState.UNVERIFIED,readOzon("approved",LocalDate.now().minusDays(1),LocalDate.now().plusYears(1),doc->doc.add("products_count",JsonParser.parseString(raw))).documents().getFirst().state());
        assertEquals(DocumentState.UNVERIFIED,row(ozon).documentState());
    }

    @Test void ozonMissingOptionalCountStillUsesExactOfferAssociationFilter()throws Exception{
        assertEquals(DocumentState.VALID,readOzon("approved",LocalDate.now().minusDays(1),LocalDate.now().plusYears(1),doc->doc.remove("products_count")).documents().getFirst().state());
        assertEquals(DocumentState.VALID,row(ozon).documentState());
    }

    @Test void wbDocumentWithoutVerdictMeansValidationIsPending()throws Exception{
        var result=readWb(null);
        assertEquals(DocumentState.PENDING,result.documents().getFirst().state());
        assertEquals(DocumentState.PENDING,row(wb).documentState());
    }

    @Test void wbUnknownVerifiedStatusCannotBeInventedAsRejection()throws Exception{
        assertEquals(DocumentState.UNVERIFIED,readWb(JsonParser.parseString("{\"verified\":true,\"status\":99}").getAsJsonObject()).documents().getFirst().state());
        assertEquals(DocumentState.UNVERIFIED,row(wb).documentState());
    }

    @Test void wbApprovedDocumentNotYetEffectiveRemainsPending()throws Exception{
        assertEquals(DocumentState.PENDING,readWb(verdict(1),null,List.of(),LocalDate.now().plusDays(1)).documents().getFirst().state());
        assertEquals(DocumentState.PENDING,row(wb).documentState());
    }

    @ParameterizedTest @ValueSource(strings={"{\"verified\":\"true\",\"status\":1}","{\"verified\":true,\"status\":\"1\"}","{\"verified\":{},\"status\":1}"})
    void wbApprovalRequiresBooleanVerificationAndNumericStatus(String raw)throws Exception{
        assertEquals(DocumentState.UNVERIFIED,readWb(JsonParser.parseString(raw).getAsJsonObject()).documents().getFirst().state());
        assertEquals(DocumentState.UNVERIFIED,row(wb).documentState());
    }

    @Test void wbVerifiedApprovalAndRejectionStayDistinct()throws Exception{
        assertEquals(DocumentState.VALID,readWb(JsonParser.parseString("{\"verified\":true,\"status\":1}").getAsJsonObject()).documents().getFirst().state());
        assertEquals(DocumentState.VALID,row(wb).documentState());
        assertEquals(DocumentState.REJECTED,readWb(JsonParser.parseString("{\"verified\":true,\"status\":2}").getAsJsonObject()).documents().getFirst().state());
        assertEquals(DocumentState.REJECTED,row(wb).documentState());
    }

    @Test void wbApprovedListingWithValidRequiredAndPendingAdditionalDocumentProjectsValid()throws Exception{
        var pending=additionalWbDocument(null,LocalDate.now().plusYears(1));
        var result=readWb(verdict(1),overall(true,1),List.of(pending),LocalDate.now().minusDays(1));
        assertEquals(DocumentState.VALID,result.documents().getFirst().state());
        assertNotEquals(DocumentState.VALID,result.documents().get(1).state(),"Listing approval must not rewrite an unverified additional document");
        assertEquals(DocumentState.VALID,row(wb).documentState(),"WB confirms the listing and its required valid document despite an additional document still awaiting validation");
        assertEquals(1,new ProductRepository().loadPage(query(wb,DocumentState.VALID)).total());
    }

    @Test void wbApprovedListingWithValidRequiredAndRejectedAdditionalDocumentProjectsValid()throws Exception{
        var result=readWb(verdict(1),overall(true,1),List.of(additionalWbDocument(verdict(2),LocalDate.now().plusYears(1))),LocalDate.now().minusDays(1));
        assertEquals(DocumentState.REJECTED,result.documents().get(1).state());
        assertEquals(DocumentState.VALID,row(wb).documentState());
    }

    @Test void wbApprovedListingWithValidRequiredAndExpiredAdditionalDocumentProjectsValid()throws Exception{
        var result=readWb(verdict(1),overall(true,1),List.of(additionalWbDocument(verdict(1),LocalDate.now().minusDays(1))),LocalDate.now().minusDays(1));
        assertEquals(DocumentState.EXPIRED,result.documents().get(1).state());
        assertEquals(DocumentState.VALID,row(wb).documentState());
    }

    @Test void wbOverallApprovalWithoutIndividuallyVerifiedCurrentDocumentCannotProveDocumentsValid()throws Exception{
        readWb(null,overall(true,1),List.of(),LocalDate.now().minusDays(1));
        assertNotEquals(DocumentState.VALID,row(wb).documentState());
    }

    @Test void wbOverallApprovalCannotUseOnlyFutureOrExpiredValidDocument()throws Exception{
        readWb(verdict(1),overall(true,1),List.of(additionalWbDocument(null,LocalDate.now().plusYears(1))),LocalDate.now().plusDays(1));
        assertNotEquals(DocumentState.VALID,row(wb).documentState());
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE product_documents SET state='VALID',payload=json_set(payload,'$.state','VALID') WHERE shop_id=2 AND document_id='77'");
        }
        assertNotEquals(DocumentState.VALID,row(wb).documentState(),"Legacy cached approval cannot override a future effective date");
        readWb(verdict(1),overall(true,1),List.of(additionalWbDocument(verdict(2),LocalDate.now().plusYears(1))),LocalDate.now().minusDays(1));
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE product_documents SET valid_until=date('now','-1 day') WHERE shop_id=2 AND document_id='77'");
        }
        assertNotEquals(DocumentState.VALID,row(wb).documentState());
    }

    @Test void wbPendingOrRejectedOverallCannotHideRejectedAdditionalDocument()throws Exception{
        for(var approval:List.of(overall(false,1),overall(true,2),overall(true,99))){
            readWb(verdict(1),approval,List.of(additionalWbDocument(verdict(2),LocalDate.now().plusYears(1))),LocalDate.now().minusDays(1));
            assertEquals(DocumentState.REJECTED,row(wb).documentState());
        }
    }

    @Test void wbHistoricalOrForeignOverallCannotPromoteDocumentProjection()throws Exception{
        var result=readWb(verdict(1),overall(true,1),List.of(additionalWbDocument(verdict(2),LocalDate.now().plusYears(1))),LocalDate.now().minusDays(1));
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=2");
        }
        assertEquals(DocumentState.UNVERIFIED,row(wb).documentState());
        assertEquals(DocumentState.VALID,result.documents().getFirst().state(),"Changing credentials does not rewrite the retained historical document payload");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE shops SET api_key='fixture' WHERE id=2");
        }
        new ProductRepository().saveSnapshot(new ProductSnapshot(result.key(),"historical:fixture",result.title(),result.description(),result.brand(),result.vendorArticle(),result.categoryId(),result.media(),result.attributes(),result.variants(),result.dimensions(),result.documents(),result.checkedAt(),result.metadata()));
        assertEquals(DocumentState.UNVERIFIED,row(wb).documentState(),"An imported historical snapshot cannot reuse a live listing verdict");
    }

    private ProductSnapshot readOzon(String status,LocalDate issue,LocalDate expiry)throws Exception{
        return readOzon(status,issue,expiry,document->{});
    }
    private ProductSnapshot readOzon(String status,LocalDate issue,LocalDate expiry,java.util.function.Consumer<JsonObject> changes)throws Exception{
        var certificate=new JsonObject();certificate.addProperty("certificate_id",77);certificate.addProperty("certificate_number","SYNTHETIC-DOC");certificate.addProperty("type_code","declaration");certificate.addProperty("status_code",status);certificate.addProperty("issue_date",issue+"T00:00:00Z");certificate.addProperty("expire_date",expiry+"T00:00:00Z");certificate.addProperty("products_count",1);changes.accept(certificate);
        var documents=new JsonObject();var items=new JsonArray();items.add(certificate);var result=new JsonObject();result.add("certificates",items);result.addProperty("page_count",1);documents.add("result",result);
        var reader=new OzonProductSnapshotReader(s->(path,body,mutation)->{
            assertFalse(mutation);
            return switch(path){
                case "v3/product/info/list"->JsonParser.parseString("{\"items\":[{\"id\":321,\"sku\":99,\"offer_id\":\"O-1\",\"name\":\"Fixture\"}]}");
                case "v4/product/info/attributes"->JsonParser.parseString("{\"result\":[{\"id\":321,\"offer_id\":\"O-1\",\"name\":\"Fixture\",\"description_category_id\":12,\"type_id\":13,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"attributes\":[]}]}");
                case "v1/description-category/attribute"->JsonParser.parseString("{\"result\":[]}");
                case "v1/product/certificate/list"->{assertEquals("O-1",body.getAsJsonObject().get("offer_id").getAsString());yield documents.deepCopy();}
                default->throw new AssertionError("Unexpected route "+path);
            };
        });
        return reader.readForSynchronization(ozon,ozonKey);
    }

    private ProductSnapshot readWb(JsonObject verdict)throws Exception{
        return readWb(verdict,null,List.of(),LocalDate.now().minusDays(1));
    }
    private ProductSnapshot readWb(JsonObject verdict,JsonObject overall,List<JsonObject> additional,LocalDate start)throws Exception{
        var response=JsonParser.parseString(ProductSnapshotReaderTest.WB).getAsJsonObject();
        var documents=response.getAsJsonArray("cards").get(0).getAsJsonObject().getAsJsonObject("documents");
        var document=documents.getAsJsonArray("items").get(0).getAsJsonObject();
        document.addProperty("startDate",start+"T00:00:00Z");document.addProperty("endDate",LocalDate.now().plusYears(1)+"T00:00:00Z");
        if(verdict==null)document.remove("verdict");else document.add("verdict",verdict);
        if(overall!=null)documents.add("overallVerdict",overall);
        additional.forEach(value->documents.getAsJsonArray("items").add(value));
        return new WbProductSnapshotReader(s->(path,body,mutation)->{assertFalse(mutation);return response.deepCopy();}).readForSynchronization(wb,wbKey);
    }
    private JsonObject verdict(int status){var v=new JsonObject();v.addProperty("verified",true);v.addProperty("status",status);return v;}
    private JsonObject overall(boolean checked,int status){var v=new JsonObject();v.addProperty("isFullyChecked",checked);v.addProperty("status",status);return v;}
    private JsonObject additionalWbDocument(JsonObject verdict,LocalDate until){
        var d=new JsonObject();d.addProperty("id",78);d.addProperty("type",2);d.addProperty("number","SYNTHETIC-ADDITIONAL");d.addProperty("startDate",LocalDate.now().minusYears(2)+"T00:00:00Z");d.addProperty("endDate",until+"T00:00:00Z");if(verdict!=null)d.add("verdict",verdict);return d;
    }
    private ProductRow row(Shop shop){return new ProductRepository().loadPage(query(shop,null)).rows().getFirst();}
    private ProductQuery query(Shop shop,DocumentState state){return new ProductQuery(shop.getId(),shop.getMarketplace(),"",List.of(),state,false,null,100);}
}
