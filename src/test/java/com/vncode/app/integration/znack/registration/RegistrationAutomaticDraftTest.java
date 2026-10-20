package com.vncode.app.integration.znack.registration;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationAutomaticDraftTest {
    @Test void completesOnlyLiteralAndSolePresetValuesIncludingConditionalFields(){
        var child=new Attribute(12,"Kind","string",true,false,true,false,List.of("Одежда"));
        var parent=new Attribute(14013,"Gender","string",true,false,true,false,List.of("Мужской"),List.of(),Map.of("Мужской",List.of(child)));
        var draft=new Draft("6204",1,"Name","Brand",Map.of(14013L,"мужской"));
        var result=RegistrationAutomaticDraft.complete(draft,List.of(parent),"52");
        assertEquals("Мужской",result.attributes().get(14013L));assertEquals("Одежда",result.attributes().get(12L));
    }
    @Test void missingAmbiguousValuesAreReportedBeforeAllocationInsteadOfGuessing(){
        var schema=List.of(new Attribute(2483,"Composition","string",true,false,true,false,List.of("Хлопок","Шерсть")));
        var draft=new Draft("6204",1,"Name","Brand",Map.of());
        var error=assertThrows(RegistrationValidationException.class,()->RegistrationAutomaticDraft.complete(draft,schema,"52"));
        assertTrue(error.getMessage().contains("Composition [2483]"));
    }
}
