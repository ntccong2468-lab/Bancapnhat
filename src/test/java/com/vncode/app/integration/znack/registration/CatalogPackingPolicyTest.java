package com.vncode.app.integration.znack.registration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CatalogPackingPolicyTest {
    @Test void onlyAConfirmedTerminalRejectionCanChangeThePackagingFlag(){
        var required=new ZnackNationalCatalogService.FeedProgress("Rejected",null,List.of("Карточка должна быть создана с типом Комплект"));
        var forbidden=new ZnackNationalCatalogService.FeedProgress("Rejected",null,List.of("is_kit не допускается. Исключите параметр"));
        assertEquals(Boolean.TRUE,CatalogPackingPolicy.correction(required,false));assertNull(CatalogPackingPolicy.correction(required,true));
        assertEquals(Boolean.FALSE,CatalogPackingPolicy.correction(forbidden,true));assertNull(CatalogPackingPolicy.correction(forbidden,false));
        assertNull(CatalogPackingPolicy.correction(new ZnackNationalCatalogService.FeedProgress("Processing",null,required.errors()),false));
        assertNull(CatalogPackingPolicy.correction(new ZnackNationalCatalogService.FeedProgress("Rejected",null,List.of("Unrelated error")),false));
    }
    @Test void combinedKitAndOptionalImageRejectionIsCorrectedTogetherWithoutChangingProductFacts(){
        var payload=com.google.gson.JsonParser.parseString("{\"gtin\":\"04631993764370\",\"tnved\":\"6112110000\",\"is_kit\":true,\"good_images\":[\"https://basket-33.wbbasket.ru/image.jpg\"],\"brand\":\"Fixture\"}").getAsJsonObject();
        var errors=List.of("Создание комплектов в товарной группе с ТНВЭД:6112110000 не допускается. Исключите параметр is_kit.","Изображение не доступно по URL https://basket-33.wbbasket.ru/image.jpg");
        var corrected=CatalogPackingPolicy.correctedPayload(new ZnackNationalCatalogService.FeedProgress("Rejected",null,errors),payload);
        assertNotNull(corrected);assertFalse(corrected.has("is_kit"));assertFalse(corrected.has("good_images"));
        assertEquals(payload.get("gtin"),corrected.get("gtin"));assertEquals(payload.get("tnved"),corrected.get("tnved"));assertEquals(payload.get("brand"),corrected.get("brand"));
        assertTrue(payload.has("is_kit"));assertTrue(payload.has("good_images"));
        assertNull(CatalogPackingPolicy.correctedPayload(new ZnackNationalCatalogService.FeedProgress("Processing",null,errors),payload));
        assertNull(CatalogPackingPolicy.correctedPayload(new ZnackNationalCatalogService.FeedProgress("Rejected",null,List.of(errors.getFirst(),"Размер не соответствует")),payload));
    }
}
