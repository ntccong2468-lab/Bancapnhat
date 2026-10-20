package com.vncode.app.features.kiz.status;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Kn3ProtocolTest {
    static JsonObject status(String first,String errors){return JsonParser.parseString("{\"posting_number\":\"101-1-1\",\"status\":\"ship_not_available\",\"products\":[{\"product_id\":501,\"exemplars\":[{\"exemplar_id\":11,\"gtd\":\"keep-gtd\",\"rnpt\":\"keep-rnpt\",\"weight\":3.25,\"is_gtd_absent\":false,\"is_rnpt_absent\":false,\"marks\":[{\"mark\":\"old\",\"mark_type\":\"mandatory_mark\",\"check_status\":\""+first+"\",\"error_codes\":"+errors+"},{\"mark\":\"keep-imei\",\"mark_type\":\"imei\",\"check_status\":\"passed\"}]},{\"exemplar_id\":12,\"marks\":[{\"mark\":\"keep-other\",\"mark_type\":\"mandatory_mark\",\"check_status\":\"passed\",\"error_codes\":[]}]}]}]}").getAsJsonObject();}
    @Test void wbUsesActualDecisionAndNeverGreensPendingOrDeadlineExceeded(){
        for(String decision:List.of("sgtinIntroduced","sgtinSoldB2B","filled","optional"))assertEquals(Kn3Mark.State.ACCEPTED,Kn3Mark.wbState(decision,"code"));
        for(String decision:List.of("pending","deadlineExceeded","unknown",""))assertEquals(Kn3Mark.State.PENDING,Kn3Mark.wbState(decision,"code"));
        for(String decision:List.of("sgtinNotFound","sgtinRetired","sgtinWrittenOff","sgtinAppliedNotPaid","sgtinNoGS"))assertEquals(Kn3Mark.State.REJECTED,Kn3Mark.wbState(decision,"code"));
        assertEquals(Kn3Mark.State.EMPTY,Kn3Mark.wbState("sgtinIntroduced",""));
    }
    @Test void oneRejectedMarkDoesNotMakeItsPassedSiblingRejected()throws Exception {
        var marks=OzonKn3Protocol.parse("101-1-1",status("processing","[\"invalid_mark\"]"));assertEquals(2,marks.size());assertEquals(Kn3Mark.State.REJECTED,marks.getFirst().state());assertEquals(Kn3Mark.State.ACCEPTED,marks.getLast().state());assertEquals(Kn3Mark.State.REJECTED,Kn3Mark.summary(marks));
    }
    @Test void ozonProcessingFailureWithoutErrorCodesIsPendingRatherThanBadKiz()throws Exception {
        var marks=OzonKn3Protocol.parse("101-1-1",status("failed","[]"));assertEquals(Kn3Mark.State.PENDING,marks.getFirst().state());assertEquals("PROCESSING_FAILED",marks.getFirst().reason());assertEquals(Kn3Mark.State.PENDING,Kn3Mark.summary(marks));
    }
    @Test void fullReplacementPreservesSiblingsOtherMarkTypesAndCustomsData()throws Exception {
        var raw=status("failed","[\"invalid_mark\"]");raw.addProperty("multi_box_qty",2);var old=OzonKn3Protocol.parse("101-1-1",raw).getFirst();var request=OzonKn3Protocol.replacement("101-1-1",raw,old,"new");var exemplars=request.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars");
        assertEquals(2,request.get("multi_box_qty").getAsInt());assertEquals("keep-gtd",exemplars.get(0).getAsJsonObject().get("gtd").getAsString());assertEquals("keep-rnpt",exemplars.get(0).getAsJsonObject().get("rnpt").getAsString());assertEquals(3.25,exemplars.get(0).getAsJsonObject().get("weight").getAsDouble());
        assertEquals("new",exemplars.get(0).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject().get("mark").getAsString());assertEquals("keep-imei",exemplars.get(0).getAsJsonObject().getAsJsonArray("marks").get(1).getAsJsonObject().get("mark").getAsString());assertEquals("keep-other",exemplars.get(1).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject().get("mark").getAsString());assertFalse(request.toString().contains("check_status"));assertFalse(request.toString().contains("error_codes"));assertEquals("old",raw.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").get(0).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject().get("mark").getAsString());
    }
    @Test void changedAcceptedOrLockedTargetsCannotBeReplaced()throws Exception {
        var raw=status("failed","[\"invalid_mark\"]");var target=OzonKn3Protocol.parse("101-1-1",raw).getFirst();
        assertThrows(java.io.IOException.class,()->OzonKn3Protocol.replacement("101-1-1",status("passed","[]"),target,"new"));
        raw.addProperty("status","update_not_available");assertThrows(java.io.IOException.class,()->OzonKn3Protocol.replacement("101-1-1",raw,target,"new"));assertThrows(java.io.IOException.class,()->OzonKn3Protocol.parse("foreign",raw));
    }
}
