package com.vncode.app.features.gs1;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static com.vncode.app.features.gs1.Gs1ApplicationStatus.State.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1ApplicationStatusTest {
    private JsonObject form(String json){return JsonParser.parseString(json).getAsJsonObject();}
    @Test void confirmedRejectionTakesPrecedenceAndRetainsTheActionableFieldReason(){
        var status=Gs1ApplicationStatus.read(form("{\"isSent\":true,\"errorText\":\"Applicant position must be corrected\"}"),false);
        assertEquals(REJECTED,status.state());assertEquals("Applicant position must be corrected",status.reason());
        var restored=status.form("7700000000","source@example.invalid");assertEquals("7700000000",restored.getAsJsonObject("applicant").get("inn").getAsString());
        assertEquals(status,Gs1ApplicationStatus.read(restored,false));assertFalse(status.toString().contains("Applicant position"));
    }
    @Test void missingNullAndMalformedStatusNeverBecomeAPendingSubmission(){
        assertEquals(UNKNOWN,Gs1ApplicationStatus.read(null,false).state());assertEquals(UNKNOWN,Gs1ApplicationStatus.read(form("{}"),false).state());
        assertEquals(UNKNOWN,Gs1ApplicationStatus.read(form("{\"isSent\":null}"),false).state());
        for(String invalid:List.of("\"true\"","[]","{}","1"))assertEquals(UNKNOWN,Gs1ApplicationStatus.read(form("{\"isSent\":"+invalid+",\"errorText\":\"Rejected\"}"),false).state());
        for(String invalid:List.of("true","[]","{}","1"))assertEquals(UNKNOWN,Gs1ApplicationStatus.read(form("{\"isSent\":true,\"errorText\":"+invalid+"}"),false).state());
        assertNull(Gs1ApplicationStatus.unknown().form("7700000000","source@example.invalid"));
    }
    @Test void normalDraftSubmittedAndAuthenticatedNewFormRemainDistinct(){
        assertEquals(DRAFT,Gs1ApplicationStatus.read(form("{\"isSent\":false}"),false).state());
        assertEquals(DRAFT,Gs1ApplicationStatus.read(form("{}"),true).state());
        for(String error:List.of("null","\"\"","\"  \""))assertEquals(SUBMITTED,Gs1ApplicationStatus.read(form("{\"isSent\":true,\"errorText\":"+error+"}"),false).state());
        var status=Gs1ApplicationStatus.read(form("{\"isSent\":true}"),false);assertEquals(status,Gs1ApplicationStatus.read(status.form("7700000000",""),false));
    }
    @Test void boundedSanitizedRejectionKeepsFieldInformationWithoutTransportSecrets(){
        var status=new Gs1ApplicationStatus(REJECTED,"Field applicant.position rejected; token=synthetic-token-value; Bearer synthetic-bearer-value\n"+"x".repeat(2000));
        assertTrue(status.reason().startsWith("Field applicant.position rejected"));assertTrue(status.reason().contains("[REDACTED]"));
        assertFalse(status.reason().contains("synthetic-token-value"));assertFalse(status.reason().contains("synthetic-bearer-value"));assertTrue(status.reason().length()<=1000);
        assertEquals("",new Gs1ApplicationStatus(SUBMITTED,"stale rejection").reason());
    }
    @Test void storedEvidenceIsBoundToTheExactSourceReadAndCorruptOrLegacyRowsAreUnknown(){
        var observed=Instant.parse("2026-11-17T12:00:00Z");var status=new Gs1ApplicationStatus(REJECTED,"Field phone is missing");
        assertEquals(status,Gs1ApplicationStatus.restore(status.stored(observed),observed));
        var escaped=new Gs1ApplicationStatus(REJECTED,"<".repeat(1000));assertEquals(escaped,Gs1ApplicationStatus.restore(escaped.stored(observed),observed),"JSON escaping must not discard a bounded rejection reason");
        for(String bad:List.of("{}","[]","not JSON","{\"state\":\"SUBMITTED\",\"reason\":false,\"observedAt\":\"2026-11-17T12:00:00Z\"}","{\"state\":\"FUTURE_STATE\",\"reason\":\"\",\"observedAt\":\"2026-11-17T12:00:00Z\"}"))assertEquals(UNKNOWN,Gs1ApplicationStatus.restore(bad,observed).state());
        assertEquals(UNKNOWN,Gs1ApplicationStatus.restore(null,observed).state());assertEquals(UNKNOWN,Gs1ApplicationStatus.restore(status.stored(observed),null).state());
        assertEquals(UNKNOWN,Gs1ApplicationStatus.restore(status.stored(observed),observed.plusSeconds(1)).state(),"An old application writer cannot leave an apparently fresh stale status sidecar");
    }
}
