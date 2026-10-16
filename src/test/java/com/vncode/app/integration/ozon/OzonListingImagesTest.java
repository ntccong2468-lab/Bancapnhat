package com.vncode.app.integration.ozon;
import com.google.gson.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OzonListingImagesTest {
    @Test void primaryImageComesFirstWithoutDuplicatingItInTheGallery(){
        var info=JsonParser.parseString("{\"primary_image\":[\"https://ir.ozone.ru/main.jpg\"]}").getAsJsonObject();
        var attributes=JsonParser.parseString("{\"images\":[\"https://ir.ozone.ru/second.jpg\",\"https://ir.ozone.ru/main.jpg\",\"https://ir.ozone.ru/third.jpg\"]}").getAsJsonObject();
        assertEquals(List.of("https://ir.ozone.ru/main.jpg","https://ir.ozone.ru/second.jpg","https://ir.ozone.ru/third.jpg"),OzonListingImages.ordered(info,attributes));
    }
    @Test void informationImagesAreUsedWhenTheAttributesEndpointHasNoGallery(){
        var info=JsonParser.parseString("{\"primary_image\":\"https://ir.ozone.ru/main.jpg\",\"images\":[{\"file_name\":\"https://ir.ozone.ru/second.jpg\"}]}").getAsJsonObject();
        assertEquals(List.of("https://ir.ozone.ru/main.jpg","https://ir.ozone.ru/second.jpg"),OzonListingImages.ordered(info,new JsonObject()));
    }
}
