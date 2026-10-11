package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.listing.ListingTransport;
import com.vncode.app.integration.wb.WbProductSnapshotReader;
import com.vncode.app.integration.ozon.OzonProductSnapshotReader;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductSnapshotReaderTest {
    @TempDir Path root;
    Shop wb=new Shop(1,"WB",Marketplace.WILDBERRIES,null,"fixture"), ozon=new Shop(2,"Ozon",Marketplace.OZON,"22","fixture");
    @BeforeEach void init() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture'),(2,'Ozon','OZON','22','fixture')");}
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    static final String WB="""
      {"cards":[{"nmID":123,"subjectID":10,"title":"Full","description":"Text","brand":"VN","vendorCode":"ARTICLE",
      "photos":[{"big":"https://basket-01.wbbasket.ru/1.jpg"},{"big":"https://basket-01.wbbasket.ru/2.jpg"}],
      "dimensions":{"length":12,"width":13,"height":14,"weightBrutto":0.123},
      "characteristics":[{"id":5,"name":"Color","value":["Black"]}],
      "sizes":[{"chrtID":101,"techSize":"M","wbSize":"46","skus":["04610689132457"]},{"chrtID":102,"techSize":"L","wbSize":"48","skus":["4610689130422"]}],
      "documents":{"items":[{"id":77,"type":2,"number":"DOC","applicant":"Owner","productNumber":"Scope","startDate":"2026-01-01T00:00:00Z","endDate":"2027-01-01T00:00:00Z","verdict":{"verified":true,"status":1}}]}}]}
      """;
    @Test void snapshotKeepsAllPhotosVariantsAndPrecision() throws Exception {
        var reader=new WbProductSnapshotReader(s->(path,body,mutation)->JsonParser.parseString(WB));
        var snapshot=reader.read(wb,new ProductKey(1,Marketplace.WILDBERRIES,"123","101"));
        assertTrue(snapshot.revision().startsWith("live:"));assertEquals(2,snapshot.media().size());assertTrue(snapshot.media().get(1).uri().toString().endsWith("2.jpg"));
        assertEquals(2,snapshot.variants().size());assertEquals("46",snapshot.variants().getFirst().russianSize());
        assertEquals(new BigDecimal("0.123"),snapshot.dimensions().weightKg());assertEquals("04610689132457",snapshot.variants().getFirst().gtin());
        assertFalse(snapshot.variants().getFirst().gtinVerified());assertEquals(DocumentState.VALID,snapshot.documents().getFirst().state());
        assertNull(snapshot.documents().getFirst().file());assertEquals("Scope",snapshot.documents().getFirst().metadata().get("productNumber"));
        assertThrows(UnsupportedOperationException.class,()->snapshot.metadata().put("secret","bad"));
    }
    @Test void shopOwnerMustMatchBeforeHttp() throws Exception {
        var calls=new AtomicInteger();ListingTransport transport=(p,b,m)->{calls.incrementAndGet();return JsonParser.parseString(WB);};
        var reader=new WbProductSnapshotReader(s->transport);
        assertThrows(SecurityException.class,()->reader.read(ozon,new ProductKey(1,Marketplace.WILDBERRIES,"123","101")));
        assertThrows(SecurityException.class,()->reader.read(new Shop(1,"WB","rotated"),new ProductKey(1,Marketplace.WILDBERRIES,"123","101")));
        assertEquals(0,calls.get());
    }
    @Test void summaryCannotPretendComplete() {
        var reader=new WbProductSnapshotReader(s->(p,b,m)->JsonParser.parseString("{\"cards\":[{\"nmID\":123,\"title\":\"Summary\"}]}"));
        assertThrows(java.io.IOException.class,()->reader.read(wb,new ProductKey(1,Marketplace.WILDBERRIES,"123","101")));
    }
    @Test void onlyPublishedOwnedTradeUnitVerifiesFreshBarcode() throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO gtin_sync_catalog VALUES(1,'WILDBERRIES','04610689132457',?)")){
            p.setString(1,"{\"published\":true,\"tradeUnit\":true}");p.executeUpdate();
            try(var s=c.createStatement()){s.execute("INSERT INTO gtin_sync_mappings VALUES(1,'WILDBERRIES','123','101','04610689132457','now')");}
        }
        var reader=new WbProductSnapshotReader(s->(p,b,m)->JsonParser.parseString(WB));
        var snapshot=reader.read(wb,new ProductKey(1,Marketplace.WILDBERRIES,"123","101"));
        assertTrue(snapshot.variants().getFirst().gtinVerified());assertFalse(snapshot.variants().get(1).gtinVerified());
        assertEquals(snapshot.revision(),reader.read(wb,snapshot.key()).revision());
    }
    @Test void documentFileMissingIsExplicitAndOzonUnitsExact() throws Exception {
        var reader=new OzonProductSnapshotReader(s->(p,b,m)->JsonParser.parseString(switch(p){
            case "v3/product/info/list" -> "{\"items\":[{\"id\":321,\"sku\":99,\"offer_id\":\"O-1\",\"name\":\"Full\"}]}";
            case "v4/product/info/attributes" -> "{\"result\":[{\"id\":321,\"offer_id\":\"O-1\",\"description_category_id\":12,\"type_id\":13,\"depth\":123,\"width\":200,\"height\":50,\"dimension_unit\":\"mm\",\"weight\":123,\"weight_unit\":\"g\",\"images\":[\"https://ir.ozone.ru/1.jpg\",\"https://ir.ozone.ru/2.jpg\"],\"attributes\":[{\"id\":4191,\"values\":[{\"value\":\"Description\"}]}],\"barcodes\":[\"04610689132457\"]}]}";
            default -> "{\"result\":{\"certificates\":[{\"certificate_id\":3,\"certificate_number\":\"DOC\",\"type_code\":\"declaration\",\"status_code\":\"unverified\"}],\"page_count\":1}}";
        }));
        var snapshot=reader.read(ozon,new ProductKey(2,Marketplace.OZON,"321","O-1"));
        assertEquals(new BigDecimal("12.3"),snapshot.dimensions().lengthCm());assertEquals(new BigDecimal("0.123"),snapshot.dimensions().weightKg());
        assertEquals("Description",snapshot.description());assertEquals(2,snapshot.media().size());assertNull(snapshot.documents().getFirst().file());
        assertEquals(DocumentState.UNVERIFIED,snapshot.documents().getFirst().state());assertEquals("12:13",snapshot.categoryId());
        assertEquals(snapshot.revision(),reader.read(ozon,snapshot.key()).revision());
    }
    @Test void completeOzonReaderPreservesComplexValuesAndFlagsAmbiguousGrouping()throws Exception{
        var reader=new OzonProductSnapshotReader(s->(p,b,m)->JsonParser.parseString(switch(p){
            case "v3/product/info/list" -> "{\"items\":[{\"id\":321,\"offer_id\":\"O-1\",\"sku\":99}]}";
            case "v4/product/info/attributes" -> "{\"result\":[{\"id\":321,\"offer_id\":\"O-1\",\"name\":\"Full\",\"description_category_id\":12,\"type_id\":13,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"attributes\":[],\"complex_attributes\":[{\"id\":900,\"complex_id\":50,\"values\":[{\"value\":\"Cotton\"}]},{\"id\":900,\"complex_id\":50,\"values\":[{\"value\":\"Wool\"}]}]}]}";
            case "v1/description-category/attribute" -> "{\"result\":[{\"id\":900,\"name\":\"Material\"}]}";
            default -> "{\"result\":{\"certificates\":[],\"page_count\":1}}";
        }));var snapshot=reader.read(ozon,new ProductKey(2,Marketplace.OZON,"321","O-1"));assertEquals(2,snapshot.attributes().size());assertEquals("50:900",snapshot.attributes().getFirst().mappingKey());assertEquals(List.of("Wool"),snapshot.attributes().get(1).values());assertEquals("true",snapshot.metadata().get("complex.ambiguous"));
    }
}
