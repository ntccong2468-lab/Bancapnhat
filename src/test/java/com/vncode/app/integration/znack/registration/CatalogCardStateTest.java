package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonParser;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCardStateTest {
    private RegisteredGtin card(String json){return new RegisteredGtin("00000000000017","A","Blue","M",true,false,"National Catalog@synthetic",Instant.now(),CatalogProductDataParser.parse(JsonParser.parseString(json).getAsJsonObject()));}
    @Test void actualRawDraftStatusIsRetainedWhenTheApiOmitsDetailedStatus(){
        var card=card("{\"good_status\":\"draft\"}");assertEquals(CatalogCardState.DRAFT,CatalogCardState.of(card));assertEquals("draft",card.details().single("@catalog_status"));
    }
    @Test void rejectionTakesPriorityOverTheOuterDraftStatus(){assertEquals(CatalogCardState.ERRORS,CatalogCardState.of(card("{\"good_status\":\"draft\",\"good_detailed_status\":[\"errors\"]}")));}
    @Test void moderationTakesPriorityOverTheOuterDraftStatus(){assertEquals(CatalogCardState.MODERATION,CatalogCardState.of(card("{\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}")));}
    @Test void unknownStateCannotBeInventedAsReadySignedOrPublished(){assertEquals(CatalogCardState.UNPUBLISHED,CatalogCardState.of(card("{\"good_status\":\"unrecognized-state\"}")));}
}
