package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.shared.I18nService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationTableTextTest {
    private static final String GTIN="00000000000017", READ="2026-10-21T12:34:00Z";
    @Test void russianSizeUsesOnlyAnExplicitObservation() {
        assertEquals("52",RegistrationTableText.russianSize(" 52 "));
        assertEquals(tr("table_size_unknown"),RegistrationTableText.russianSize(""));
        assertEquals(tr("table_size_unknown"),RegistrationTableText.russianSize(null));
    }
    @Test void aLocalFeedCheckpointWithoutObservedCardCannotMeanCatalogSuccess() {
        assertEquals(tr("table_catalog_unknown"),RegistrationTableText.moderation(Optional.empty(),false));
        assertEquals(tr("table_latest_unknown"),RegistrationTableText.moderation(Optional.empty(),true));
    }
    @Test void moderationRetainsTheActualRemoteStateAndObservationTime() {
        var text=RegistrationTableText.moderation(Optional.of(card(false,"draft","moderation")),false);
        assertTrue(text.contains(tr("source.moderation")));assertTrue(text.contains("draft / moderation"));
        assertTrue(text.contains("21.10.2026"));
    }
    @Test void aPublishedCardWithAnUnpublishedFailedRevisionShowsBothFacts() {
        var text=RegistrationTableText.moderation(Optional.of(card(true,"published","errors")),false);
        assertTrue(text.contains(tr("table_published_revision_error")));assertTrue(text.contains("published / errors"));
    }
    @Test void rejectedAndAwaitingSignatureRemainDistinctFromAnOrdinaryDraft() {
        var rejected=RegistrationTableText.moderation(Optional.of(card(false,"draft","errors")),false);
        assertTrue(rejected.contains(tr("source.errors")));assertTrue(rejected.contains("errors"));
        var unsigned=RegistrationTableText.moderation(Optional.of(card(false,"draft","notsigned")),false);
        assertTrue(unsigned.contains(tr("status.ready_to_sign")));assertTrue(unsigned.contains("notsigned"));
        assertFalse(unsigned.contains(tr("source.errors")));
    }
    @Test void aFailedFreshReadNeverPromotesHistoricalCatalogOrMarketplaceProof() {
        var observed=Optional.of(card(true,"published","published"));
        var historical=RegistrationTableText.moderation(observed,true);
        assertTrue(historical.startsWith(tr("table_latest_unknown")));assertTrue(historical.contains("published"));
        var market=RegistrationTableText.marketplace(observed,List.of(GTIN),true,READ,"znack.registration.awaiting_wb_gtin");
        assertTrue(market.contains(tr("table_latest_unknown")));assertFalse(market.contains(tr("table_marketplace_verified")));
    }
    @Test void publicationAndMarketplaceReadbackAreIndependent() {
        var proof=Optional.of(card(true,"published","published"));
        var pending=RegistrationTableText.marketplace(proof,List.of("4006381333931"),false,READ,"znack.registration.awaiting_wb_gtin");
        assertTrue(pending.contains(tr("awaiting_wb_gtin")));assertFalse(pending.contains(tr("table_marketplace_verified")));
        var verified=RegistrationTableText.marketplace(proof,List.of(GTIN),false,READ,"znack.registration.awaiting_wb_gtin");
        assertTrue(verified.contains(tr("table_marketplace_verified")));assertTrue(verified.contains("21.10.2026"));
    }
    @Test void absentSourceReceiptAndUnpublishedCardsCannotMeanMarketplaceSuccess() {
        var published=Optional.of(card(true,"published","published"));
        for(String receipt:List.of("","not-a-timestamp"))
            assertTrue(RegistrationTableText.marketplace(published,List.of(GTIN),false,receipt,"znack.registration.awaiting_ozon_gtin").contains(tr("table_latest_unknown")));
        assertTrue(RegistrationTableText.marketplace(Optional.of(card(false,"draft","moderation")),List.of(GTIN),false,READ,"znack.registration.awaiting_ozon_gtin").contains(tr("table_wait_publication")));
    }
    private static RegisteredGtin card(boolean published,String status,String detailed) {
        var details=new CatalogProductData(Map.of("@catalog_status",List.of(status),"@catalog_detailed_status",List.of(detailed)),List.of(),List.of());
        return new RegisteredGtin(GTIN,"synthetic-article","Blue","M",true,published,"fixture",Instant.parse(READ),details);
    }
    private static String tr(String key){return I18nService.getInstance().tr("znack.registration."+key);}
}
