package com.vncode.app.integration.ozon;

import com.vncode.app.features.products.ProductAttribute;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonExplicitSizeReaderTest {
    @TempDir Path root;
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(2,'Ozon','OZON','22','fixture')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private static ProductAttribute size(String name,List<String> values,String complex){return new ProductAttribute("1",name,values,"",complex,0);}
    @Test void identicalExplicitSizesAreAcceptedButContradictionsAreNotGuessed(){
        var duplicate=List.of(size("Размер производителя",List.of("XL","XL"),"0"),size("Размер производителя",List.of("XL"),""));
        assertEquals(new OzonProductSnapshotReader.ExplicitSize("XL",false),OzonProductSnapshotReader.uniqueExplicitSize(duplicate,false));
        var ambiguous=List.of(size("Размер производителя",List.of("XL","2XL"),"0"));
        assertEquals(new OzonProductSnapshotReader.ExplicitSize("",true),OzonProductSnapshotReader.uniqueExplicitSize(ambiguous,false));
        var russian=List.of(size("Российский размер",List.of("52"),"0"),size("Российский размер",List.of("54"),"0"));
        assertEquals(new OzonProductSnapshotReader.ExplicitSize("",true),OzonProductSnapshotReader.uniqueExplicitSize(russian,true));
    }
    @Test void complexAttributesFromOtherGroupsCannotSupplyOrOverrideSelectedProductSize(){
        var foreign=List.of(size("Размер производителя",List.of("2XL"),"123"));
        assertEquals(new OzonProductSnapshotReader.ExplicitSize("",false),OzonProductSnapshotReader.uniqueExplicitSize(foreign,false));
        var combined=List.of(size("Размер производителя",List.of("XL"),"0"),size("Размер производителя",List.of("2XL"),"123"));
        assertEquals(new OzonProductSnapshotReader.ExplicitSize("XL",false),OzonProductSnapshotReader.uniqueExplicitSize(combined,false));
    }
    @Test void conflictIsCarriedFromMarketplaceReadIntoTheSelectedVariant()throws Exception{
        var shop=new Shop(2,"Ozon",Marketplace.OZON,"22","fixture");
        var reader=new OzonProductSnapshotReader(s->(path,body,mutation)->JsonParser.parseString(switch(path){
            case "v3/product/info/list" -> "{\"items\":[{\"id\":321,\"offer_id\":\"ARTICLE\",\"sku\":99}]}";
            case "v4/product/info/attributes" -> "{\"result\":[{\"id\":321,\"offer_id\":\"ARTICLE\",\"name\":\"Full\",\"description_category_id\":12,\"type_id\":13,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"attributes\":[{\"id\":1001,\"values\":[{\"value\":\"M\"},{\"value\":\"L\"}]},{\"id\":1002,\"values\":[{\"value\":\"46\"}]}]}]}";
            case "v1/description-category/attribute" -> "{\"result\":[{\"id\":1001,\"name\":\"Размер производителя\"},{\"id\":1002,\"name\":\"Российский размер\"}]}";
            default -> "{\"result\":{\"certificates\":[],\"page_count\":1}}";
        }));
        var snapshot=reader.readForSynchronization(shop,new ProductKey(2,Marketplace.OZON,"321","ARTICLE"));
        assertEquals("true",snapshot.metadata().get("source.size.conflict"));assertEquals("",snapshot.variants().getFirst().size());assertEquals("",snapshot.variants().getFirst().russianSize());
    }
}
