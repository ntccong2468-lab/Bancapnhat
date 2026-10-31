package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.sync.RegistrationCardIndex;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WbRejectedReplacementVisibilityTest {
    private static Sku row(Status status) {
        return new Sku(101,11,1,"article","category","brand","name","blue","L",List.of(),"",false,
                "04631993764363",90L,"feed",status,"rejected",false,"L");
    }
    @Test void onlyAnExactRejectedCardCanOfferReviewWhenTheCatalogViewIsBlocked() {
        Sku row=row(Status.ERROR);
        assertTrue(ZnackCardRegistrationController.replaceable(row,new RegistrationCardIndex.View("source.errors",row.gtin(),"CATALOG_CARD_ERRORS",true,false,true)));
        assertFalse(ZnackCardRegistrationController.replaceable(row,new RegistrationCardIndex.View("source.errors","04631993764370","CATALOG_CARD_ERRORS",true,false,true)));
        assertFalse(ZnackCardRegistrationController.replaceable(row,new RegistrationCardIndex.View("source.ambiguous",row.gtin(),"GTIN_VARIANT_CONFLICT",true,false,true)));
        assertFalse(ZnackCardRegistrationController.replaceable(row,new RegistrationCardIndex.View("source.moderation",row.gtin(),"CATALOG_CARD_MODERATION",true,false,true)));
        assertFalse(ZnackCardRegistrationController.replaceable(row(Status.PROCESSING),new RegistrationCardIndex.View("source.errors",row.gtin(),"CATALOG_CARD_ERRORS",true,false,true)));
    }
}
