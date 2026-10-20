package com.vncode.app.integration.znack.registration;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationAutomaticDraftTest {
    @Test void normalizesActualLiteralValuesAndValidatesConditionalFields(){
        var child=new Attribute(12,"Kind","string",true,false,true,false,List.of("Одежда"));
        var parent=new Attribute(14013,"Gender","string",true,false,true,false,List.of("Мужской"),List.of(),Map.of("Мужской",List.of(child)));
        var draft=new Draft("6204",1,"Name","Brand",Map.of(14013L,"мужской",12L,"одежда"));
        var result=RegistrationAutomaticDraft.complete(draft,List.of(parent),"52");
        assertEquals("Мужской",result.attributes().get(14013L));assertEquals("Одежда",result.attributes().get(12L));
    }
    @Test void aSoleConditionalOptionIsNotAReplacementForTheProductsMissingKind(){
        var child=new Attribute(12,"Kind","string",true,false,true,false,List.of("Одежда"));
        var parent=new Attribute(14013,"Gender","string",true,false,true,false,List.of("Мужской"),List.of(),Map.of("Мужской",List.of(child)));
        var draft=new Draft("6204",1,"Name","Brand",Map.of(14013L,"мужской"));
        var error=assertThrows(RegistrationValidationException.class,()->RegistrationAutomaticDraft.complete(draft,List.of(parent),"52"));
        assertTrue(error.getMessage().contains("Kind [12]"));
        assertFalse(draft.attributes().containsKey(12L));
    }
    @Test void missingAmbiguousValuesAreReportedBeforeAllocationInsteadOfGuessing(){
        var schema=List.of(new Attribute(2483,"Composition","string",true,false,true,false,List.of("Хлопок","Шерсть")));
        var draft=new Draft("6204",1,"Name","Brand",Map.of());
        var error=assertThrows(RegistrationValidationException.class,()->RegistrationAutomaticDraft.complete(draft,schema,"52"));
        assertTrue(error.getMessage().contains("Composition [2483]"));
    }
    @Test void missingBusinessFieldsDoNotBecomeTheSolePresetDuringWorkerCompletion(){
        for(var field:List.of(
                new Attribute(14013,"Gender","string",true,false,true,false,List.of("УНИСЕКС")),
                new Attribute(2504,"Brand","string",true,false,true,false,List.of("Нет бренда")),
                new Attribute(13836,"Regulation","string",true,false,true,false,List.of("ТР ТС 017/2011")),
                new Attribute(2630,"Country","string",true,false,true,false,List.of("RU")),
                new Attribute(2483,"Composition","string",true,false,true,false,List.of("Хлопок")))){
            String brand=field.id()==2504L?"":"Actual brand";
            var draft=new Draft("6204",1,"Actual name",brand,Map.of());
            var error=assertThrows(RegistrationValidationException.class,()->RegistrationAutomaticDraft.complete(draft,List.of(field),"52"),field.name());
            assertTrue(error.getMessage().contains("["+field.id()+"]"),error.getMessage());
            assertTrue(draft.attributes().isEmpty());
        }
    }
    @Test void missingNameCannotBeReplacedByTheOnlyAllowedName(){
        var field=new Attribute(2478,"Name","string",true,false,true,false,List.of("Example product"));
        assertThrows(RegistrationValidationException.class,()->RegistrationAutomaticDraft.complete(new Draft("6204",1,"","Brand",Map.of()),List.of(field),"52"));
    }
    @Test void actualGarmentInflectionUsesCurrentSchemaAndPreservesSavedIdentity(){
        var field=new Attribute(12,"Kind","string",true,false,true,false,List.of("КОСТЮМ","КОСТЮМ СПОРТИВНЫЙ"));
        var source=new Draft("6103220000","6103",30733,"Actual name","Actual brand",Map.of(12L,"Костюмы спортивные"),Map.of(),true,true);
        var result=RegistrationAutomaticDraft.complete(source,List.of(field),"52");
        assertEquals("КОСТЮМ СПОРТИВНЫЙ",result.attributes().get(12L));
        assertEquals(source.tnved(),result.tnved());assertEquals(source.feedTnved(),result.feedTnved());assertEquals(source.categoryId(),result.categoryId());assertTrue(result.autoSign());assertTrue(result.isKit());
        assertEquals("Костюмы спортивные",source.attributes().get(12L));
    }
}
