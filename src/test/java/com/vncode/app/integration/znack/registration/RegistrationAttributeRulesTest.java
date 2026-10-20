package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationAttributeRulesTest {
    @Test void aConditionalAttributeIsRequiredOnlyWhenItsTriggerIsSelected(){
        var child=new Attribute(23828,"Вид товара (при отсутствии в справочнике)","text",false,false,false,true,List.of());
        var parent=new Attribute(12,"Вид товара","text",true,false,true,false,List.of("Костюм","НЕТ В СПРАВОЧНИКЕ"),List.of(),Map.of("НЕТ В СПРАВОЧНИКЕ",List.of(child)));
        assertEquals(List.of(12L),RegistrationAttributeRules.required(List.of(parent),Map.of(12L,"Костюм")).stream().map(Attribute::id).toList());
        assertEquals(List.of(12L,23828L),RegistrationAttributeRules.required(List.of(parent),Map.of(12L,"НЕТ В СПРАВОЧНИКЕ")).stream().map(Attribute::id).toList());
    }
    @Test void conditionalReferencesUseTheCurrentModelRatherThanInventedPresets(){
        var reference=new Attribute(23828,"","",false,false,false,true,List.of());
        var actual=new Attribute(23828,"Current Catalog label","date",false,false,false,true,List.of());
        var parent=new Attribute(12,"Type","text",true,false,true,false,List.of("OTHER"),List.of(),Map.of("OTHER",List.of(reference)));
        var resolved=RegistrationAttributeRules.resolve(List.of(parent),List.of(parent,actual));var child=RegistrationAttributeRules.required(resolved,Map.of(12L,"OTHER")).getLast();
        assertEquals("Current Catalog label",child.name());assertEquals("date",child.fieldType());
    }
    @Test void numberAndDateFieldsRejectNonValuesAndImpossibleDates(){
        var number=new Attribute(1,"Weight","number",false,false,true,false,List.of());var date=new Attribute(2,"Date","date",false,false,true,false,List.of());
        assertTrue(RegistrationAttributeRules.validFieldValue(number,"12.5"));assertFalse(RegistrationAttributeRules.validFieldValue(number,"twelve"));assertFalse(RegistrationAttributeRules.validFieldValue(number,"NaN"));
        assertTrue(RegistrationAttributeRules.validFieldValue(date,"2026-10-19"));assertFalse(RegistrationAttributeRules.validFieldValue(date,"2026-02-30"));
    }
}
