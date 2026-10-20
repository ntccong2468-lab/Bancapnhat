package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPresetNormalizerTest {
    @Test void actualMarketplaceSportsuitPluralResolvesTheUniqueCurrentCatalogLiteral(){
        var schema=preset(12,"Вид товара","КОСТЮМ","КОСТЮМ СПОРТИВНЫЙ","КОМПЛЕКТ");
        assertEquals("КОСТЮМ СПОРТИВНЫЙ",RegistrationPresetNormalizer.resolve(schema,"\u00a0Костюмы спортивные\u200b"));
        assertEquals("КОСТЮМ СПОРТИВНЫЙ",RegistrationPresetNormalizer.resolve(schema,"Спортивный костюм"));
    }

    @Test void resolvesKnownGrammaticalFormsWithoutChangingTheGarment(){
        var schema=preset(12,"Вид товара","ДЖЕМПЕР","КУРТКА","ЖИЛЕТ","КОМПЛЕКТ","КОСТЮМ");
        assertEquals("ДЖЕМПЕР",RegistrationPresetNormalizer.resolve(schema,"Джемперы"));
        assertEquals("КУРТКА",RegistrationPresetNormalizer.resolve(schema,"Куртки"));
        assertEquals("ЖИЛЕТ",RegistrationPresetNormalizer.resolve(schema,"Жилеты"));
        assertEquals("КОМПЛЕКТ",RegistrationPresetNormalizer.resolve(schema,"Комплекты"));
        assertEquals("КОСТЮМ",RegistrationPresetNormalizer.resolve(schema,"Костюмы"));
    }

    @Test void doesNotCollapseSportSuitIntoTrousersSuitOrProductKit(){
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","КОСТЮМ БРЮЧНЫЙ","КОМПЛЕКТ"),"Костюмы спортивные"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","КОМПЛЕКТ"),"Костюмы"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","КОСТЮМ"),"Комплекты"));
    }

    @Test void preservesQualifiersAndRejectsMultipleEquivalentSchemaOptions(){
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","КОСТЮМ СПОРТИВНЫЙ"),"Костюм спортивный трикотажный"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","КОСТЮМ СПОРТИВНЫЙ","СПОРТИВНЫЙ КОСТЮМ"),"Костюмы спортивные"));
        assertEquals("СПОРТИВНЫЙ КОСТЮМ",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","КОСТЮМ СПОРТИВНЫЙ","СПОРТИВНЫЙ КОСТЮМ"),"СПОРТИВНЫЙ КОСТЮМ"),"An exact current-schema value does not require an alias choice");
    }

    @Test void similarColorWordsAndUnknownSourceDoNotGainAnAutomaticValue(){
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(36,"Цвет","СВЕТЛО-СИНИЙ","ТЕМНО-СИНИЙ"),"Синий"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(14013,"Целевой пол","УНИСЕКС"),""));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","КОСТЮМ СПОРТИВНЫЙ"),""));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(12,"Вид товара","---"),"..."));
    }

    @Test void usesOnlyTheActualRegulationCodeAndAnUnambiguousSchemaOption(){
        String adult="ТР ТС 017/2011 О безопасности продукции легкой промышленности";
        String children="ТР ТС 007/2011 О безопасности продукции, предназначенной для детей и подростков";
        assertEquals(children,RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",adult,children),"ТР ТС 007/2011"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",adult),""));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",adult),"007/2011"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",children,"ТР ТС 007/2011 Другая запись"),"007/2011"));
    }

    @Test void explicitRegulationJurisdictionCannotBeChangedByAnOtherwiseMatchingCode(){
        String customs="ТР ТС 007/2011 О безопасности продукции для детей";
        String union="ТР ЕАЭС 007/2011 Другая юрисдикция";
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",union),"ТР ТС 007/2011"));
        assertEquals(customs,RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",customs,union),"тр тс 007 / 2011"));
        assertEquals(union,RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",customs,union),"ТР ЕАЭС 007/2011"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",customs,union),"007/2011"));
        assertEquals("",RegistrationPresetNormalizer.resolve(preset(13836,"Технический регламент",customs+"; ТР ТС 017/2011"),"007/2011"));
    }

    @Test void returnsLiteralSchemaSpellingAndPreservesUnrestrictedProductText(){
        assertEquals("ЧЁРНЫЙ",RegistrationPresetNormalizer.resolve(preset(36,"Цвет","ЧЁРНЫЙ","БЕЛЫЙ"),"черный"));
        var freeText=new Attribute(2483,"Состав","text",false,false,true,false,List.of());
        assertEquals("Хлопок 80%,  полиэстер 20%",RegistrationPresetNormalizer.resolve(freeText,"\u00a0Хлопок 80%,  полиэстер 20%\u200b"));
    }

    private static Attribute preset(long id,String name,String... values){
        return new Attribute(id,name,"text",true,false,true,false,List.of(values));
    }
}
