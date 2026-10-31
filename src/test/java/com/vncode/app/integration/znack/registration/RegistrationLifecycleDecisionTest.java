package com.vncode.app.integration.znack.registration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.vncode.app.integration.znack.registration.RegistrationLifecycleDecision.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;

class RegistrationLifecycleDecisionTest {
    @Test void feedOrKnownCardAlwaysUsesReadbackEvenAfterAnUncertainSend() {
        for (Status status : Status.values()) {
            assertEquals(Next.READBACK, next(new Evidence(status, true, true, true)), status.name());
        }
    }
    @Test void unknownRequestsCannotBeSubmittedOrAllocatedAgain() {
        for (Status status : new Status[]{Status.ALLOCATING_GTIN, Status.GTIN_REVIEW_REQUIRED,
                Status.GTIN_GENERATED, Status.FEED_SUBMITTING, Status.FEED_REVIEW_REQUIRED,
                Status.FEED_SUBMITTED, Status.READY_TO_SIGN, Status.SIGNING, Status.PUBLISHED}) {
            assertEquals(Next.RECONCILE, next(new Evidence(status, false, false, true)), status.name());
        }
        assertEquals(Next.RECONCILE, next(new Evidence(Status.CHECKING, false, true, false)));
        assertEquals(Next.RECONCILE, next(new Evidence(Status.READY_TO_SUBMIT, false, false, false)));
    }
    @Test void actualWbAndOzonAdaptersRefuseUnknownAndPrioritizeFeed() {
        var wb=new ZnackCardRegistrationModels.Sku(1,2,0,"article","","","","","",java.util.List.of(),"",true,
                "123",null,"",Status.FEED_REVIEW_REQUIRED,"",false,"");
        assertEquals(Next.RECONCILE, ZnackRegistrationBatchService.lifecycle(wb));
        var key=new com.vncode.app.features.products.ProductKey(1,com.vncode.app.integration.marketplace.Marketplace.OZON,"1","offer");
        var ozon=new OzonCardRegistrationRepository.Row(key,"1",Status.FEED_REVIEW_REQUIRED,"123","","",null,
                "","","",null,"","","");
        assertEquals(Next.RECONCILE, OzonRegistrationBatchService.lifecycle(ozon));
        var waiting=new OzonCardRegistrationRepository.Row(key,"1",Status.FEED_REVIEW_REQUIRED,"123","","feed",null,
                "","","",null,"","","");
        assertEquals(Next.READBACK, OzonRegistrationBatchService.lifecycle(waiting));
        assertEquals(Next.PREPARE, OzonRegistrationBatchService.lifecycle(null));
    }
    @Test void knownUnsentRequestContinuesWithSavedIdentityOrPreparation() {
        assertEquals(Next.SUBMIT_SAVED, next(new Evidence(Status.READY_TO_SUBMIT, false, false, true)));
        assertEquals(Next.PREPARE, next(new Evidence(Status.NOT_CREATED, false, false, false)));
        assertEquals(Next.PREPARE, next(new Evidence(Status.PREFLIGHT_ERROR, false, false, false)));
    }
}
