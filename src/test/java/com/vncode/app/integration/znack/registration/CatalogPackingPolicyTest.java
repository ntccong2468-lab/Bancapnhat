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
}
