package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRegistrationDocumentsTest {
    private static final LocalDate TODAY=LocalDate.of(2026,10,14);
    private ProductDocument document(String id,DocumentState state,LocalDate end) {
        return new ProductDocument(id,"CONFORMITY_DECLARATION","permit-"+id,TODAY.minusYears(1),end,"","",null,
                Marketplace.OZON,state,"https://api-seller.ozon.ru/documents",TODAY.atStartOfDay().toInstant(ZoneOffset.UTC));
    }
    private ProductSnapshot snapshot(ProductDocument...docs) {
        return new ProductSnapshot(new ProductKey(1,Marketplace.OZON,"101","offer"),"live:fixture","","","","","",
                List.of(),List.of(),List.of(),null,List.of(docs),Instant.now());
    }
    @Test void usesOneCurrentlyVerifiedProductPermit() {
        var result=ZnackRegistrationDocuments.select(snapshot(document("a",DocumentState.VALID,TODAY.plusDays(1))),null,TODAY);
        assertNotNull(result.automatic());assertEquals(ZnackRegistrationDocuments.Source.PRODUCT,result.source());assertFalse(result.requiresReview());
    }
    @Test void unverifiedAndUndatedExpiryRequireReview() {
        assertNull(ZnackRegistrationDocuments.select(snapshot(document("a",DocumentState.UNVERIFIED,TODAY.plusDays(1))),null,TODAY).automatic());
        assertNull(ZnackRegistrationDocuments.select(snapshot(document("a",DocumentState.VALID,null)),null,TODAY).automatic());
    }
    @Test void multipleValidPermitsRequireAnExplicitChoice() {
        var result=ZnackRegistrationDocuments.select(snapshot(document("a",DocumentState.VALID,TODAY.plusDays(1)),document("b",DocumentState.VALID,TODAY.plusDays(2))),null,TODAY);
        assertEquals(2,result.candidates().size());assertNull(result.automatic());
    }
    @Test void expiredPendingRejectedNeverBecomeAutomaticOrShopFallback() {
        for(var state:List.of(DocumentState.EXPIRED,DocumentState.PENDING,DocumentState.REJECTED)) {
            var result=ZnackRegistrationDocuments.select(snapshot(document("a",state,TODAY.minusDays(1))),null,TODAY);
            assertTrue(result.candidates().isEmpty());assertEquals(ZnackRegistrationDocuments.Source.PRODUCT,result.source());
        }
    }
}
