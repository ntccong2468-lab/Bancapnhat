package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPermitValidatorTest {
    final GoodsDocument permit=new GoodsDocument("CONFORMITY_DECLARATION","FIXTURE","2026-06-07");
    final LocalDate today=LocalDate.of(2026,10,19);
    JsonObject registry(String scope){return JsonParser.parseString("""
        {"result":{"documents":[{"attr_id":23557,"number":"FIXTURE","from_date":"2026-06-07",
          "to_date":"2031-06-03","status_group":1,"product_tnved":"%s"}]}}
        """.formatted(scope)).getAsJsonObject();}
    @Test void activePermitDoesNotCoverUnrelatedProductsFromTheSameShop(){
        for(String code:new String[]{"6110209100","6106909000","6103420001"})
            assertTrue(RegistrationPermitValidator.problem(registry("6101, 6201, 6202, 6211"),permit,code,today).contains("ZNACK_DOCUMENT_TNVED_MISMATCH"));
        assertEquals("",RegistrationPermitValidator.problem(registry("6101, 6201, 6202, 6211"),permit,"6201400000",today));
    }
    @Test void inactiveExpiredMissingAndPendingAreNeverAssumedValid(){
        var r=registry("6110");r.getAsJsonObject("result").getAsJsonArray("documents").get(0).getAsJsonObject().addProperty("status_group",3);
        assertEquals("ZNACK_DOCUMENT_INACTIVE",RegistrationPermitValidator.problem(r,permit,"6110209100",today));
        r=registry("6110");r.getAsJsonObject("result").getAsJsonArray("documents").get(0).getAsJsonObject().addProperty("to_date","2020-01-01");
        assertEquals("ZNACK_DOCUMENT_EXPIRED",RegistrationPermitValidator.problem(r,permit,"6110209100",today));
        assertEquals("ZNACK_DOCUMENT_TNVED_SCOPE_UNKNOWN",RegistrationPermitValidator.problem(registry(""),permit,"6110209100",today));
        assertEquals("ZNACK_DOCUMENT_REGISTRY_PENDING",RegistrationPermitValidator.problem(JsonParser.parseString("{\"result\":{\"errors\":[{\"error_code\":18}]}}"),permit,"6110209100",today));
    }
    @Test void prefixesAndRangesRequireTheActualScope(){
        assertTrue(RegistrationPermitValidator.covers("6101–6103, 6211","6103420001"));
        assertFalse(RegistrationPermitValidator.covers("6101–6103, 6211","6106909000"));
        assertFalse(RegistrationPermitValidator.covers("6110209100","6110"));
        assertFalse(RegistrationPermitValidator.covers("6110209100","6110209900"));
    }
    @Test void tariffGenderContradictionCannotBeRegisteredEvenWithAnActivePermit(){
        assertTrue(RegistrationTariffRules.genderProblem("6106909000","МУЖСКОЙ").contains("ZNACK_TNVED_GENDER_MISMATCH"));
        assertEquals("",RegistrationTariffRules.genderProblem("6106909000","ЖЕНСКИЙ"));
        assertEquals("",RegistrationTariffRules.genderProblem("6110209100","МУЖСКОЙ"));
    }
}
