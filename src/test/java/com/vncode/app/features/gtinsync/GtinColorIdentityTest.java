package com.vncode.app.features.gtinsync;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GtinColorIdentityTest {
    @Test void completeSetsRoundTripWithoutLosingShadesOrTheSecondColor(){
        String value=GtinColorIdentity.value(List.of("White","Black","чёрный","dark grey"));
        assertEquals("White / Black / dark grey",value);
        assertTrue(OzonSkuGtinMatcher.sameColor(value,"тёмно–серый; белый; черный"));
        assertEquals(GtinColorIdentity.normalize(value),GtinColorIdentity.normalize(GtinColorIdentity.normalize(value)));
        for(String incomplete:List.of("Black / White","White / grey / black","dark grey"))assertFalse(OzonSkuGtinMatcher.sameColor(value,incomplete));
    }
    @Test void nativeAndSqlArraysRetainAllColorAttributesAndIgnoreNonColorAttributes(){
        var sql=new com.google.gson.JsonArray();
        for(var entry:List.of(List.of("Цвет товара","[\"Black\",\"White\"]"),List.of("Color","\"black\""),List.of("Размер","[\"XL\"]"))){
            var attribute=new com.google.gson.JsonObject();attribute.addProperty("name",entry.getFirst());attribute.addProperty("value",entry.getLast());sql.add(attribute);
        }
        assertEquals("Black / White",GtinColorIdentity.fromWbCharacteristics(sql.toString()));
        assertEquals("серый",GtinColorIdentity.fromWbCharacteristics("[{\"name\":\"Цвет\",\"value\":[\"серый\"]}]"));
    }
    @Test void malformedOrNonTextColorNeverBecomesPartialPositiveEvidence(){
        for(String value:List.of("not-json","{}","[null]","[{\"name\":\"Color\",\"value\":null}]",
                "[{\"name\":\"Color\",\"value\":\"[\\\"black\\\",3]\"}]",
                "[{\"name\":\"Color\",\"value\":\"[\\\"black\\\",{}]\"}]"))
            assertEquals("",GtinColorIdentity.fromWbCharacteristics(value),value);
    }
}
