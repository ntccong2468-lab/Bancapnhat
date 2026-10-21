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
    @Test void nativeMarketplaceDocumentTypeCodesRemainEligibleWithoutReplacingThePermit(){
        for(String type:List.of("1","2","CERTIFICATE_OF_CONFORMITY","DECLARATION_OF_CONFORMITY")){
            var original=document("a",DocumentState.VALID,TODAY.plusDays(1));var supplied=new ProductDocument(original.id(),type,original.number(),original.validFrom(),original.validUntil(),original.owner(),original.scope(),original.file(),original.marketplace(),original.state(),original.source(),original.checkedAt());
            var selection=ZnackRegistrationDocuments.select(snapshot(supplied),null,TODAY);assertNotNull(selection.automatic());assertEquals(original.number(),selection.automatic().number());assertEquals(type.equals("1")||type.equals("CERTIFICATE_OF_CONFORMITY")?"CONFORMITY_CERTIFICATE":"CONFORMITY_DECLARATION",selection.automatic().type());
        }
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
