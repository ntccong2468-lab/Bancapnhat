package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationVerifiedDefaultsTest {
    private static final String REGULATION="ТР ТС 017/2011 \"О безопасности продукции легкой промышленности\"";
    private final Attribute regulation=new Attribute(13836,"Технический регламент","text",true,false,true,false,List.of(REGULATION));
    private Draft draft(Map<Long,String> values){return new Draft("6112","6112",1,"Source name","Source brand",values,Map.of(),true,true);}

    @Test void fillsOnlyVerifiedMissingSchemaLiteralAndPreservesIdentityAndOriginal(){
        var before=draft(Map.of(12L,"Source kind"));
        var after=RegistrationVerifiedDefaults.apply(before,List.of(regulation),Map.of(13836L," "+REGULATION+" ",2504L,"Another brand"),null);
        assertEquals(REGULATION,after.attributes().get(13836L));assertEquals("",after.attributeTypes().get(13836L));
        assertFalse(before.attributes().containsKey(13836L));assertEquals("Source kind",after.attributes().get(12L));
        assertEquals(before.tnved(),after.tnved());assertEquals(before.feedTnved(),after.feedTnved());assertEquals(before.categoryId(),after.categoryId());
        assertEquals(before.goodName(),after.goodName());assertEquals(before.brand(),after.brand());assertTrue(after.autoSign());assertTrue(after.isKit());
    }
    @Test void sourceValueAndLaterSellerOverrideAlwaysTakePrecedence(){
        var explicit=draft(Map.of(13836L,"Existing explicit value"));
        assertSame(explicit,RegistrationVerifiedDefaults.apply(explicit,List.of(regulation),Map.of(13836L,REGULATION),null));
        var automatic=RegistrationVerifiedDefaults.apply(draft(Map.of()),List.of(regulation),Map.of(13836L,REGULATION),null);
        var reviewed=RegistrationReviewedValues.apply(automatic,Map.of(13836L,new RegistrationReviewedValues.Value("Seller-reviewed value","")));
        assertEquals("Seller-reviewed value",reviewed.attributes().get(13836L));
    }
    @Test void invalidExplicitSourceIsNotSilentlyReplacedWhenMapperLeftItMissing(){
        var source=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","201"),"read","Name","","Brand","Article","1",List.of(),
                List.of(new ProductAttribute("source-regulation","Технический регламент",List.of("Unknown regulation"),"")),List.of(),null,List.of(),Instant.now());
        var before=draft(Map.of());
        assertSame(before,RegistrationVerifiedDefaults.apply(before,List.of(regulation),Map.of(13836L,REGULATION),source));
    }
    @Test void foreignPresetMissingSchemaUnsupportedIdentityAndAmbiguousTypeRemainUnfilled(){
        var before=draft(Map.of());
        assertSame(before,RegistrationVerifiedDefaults.apply(before,List.of(regulation),Map.of(13836L,"Other regulation"),null));
        assertSame(before,RegistrationVerifiedDefaults.apply(before,List.of(),Map.of(13836L,REGULATION),null));
        assertSame(before,RegistrationVerifiedDefaults.apply(before,List.of(new Attribute(2504,"Brand","text",false,false,true,false,List.of())),Map.of(2504L,"Default brand"),null));
        var ambiguous=new Attribute(13836,"Regulation","text",true,false,true,false,List.of(REGULATION),List.of("A","B"));
        assertSame(before,RegistrationVerifiedDefaults.apply(before,List.of(ambiguous),Map.of(13836L,REGULATION),null));
    }
    @Test void defaultsDoNotActivateUnselectedConditionalFields(){
        var parent=new Attribute(999,"Choice","text",true,false,true,false,List.of("A","B"),List.of(),Map.of("A",List.of(regulation)));
        var before=draft(Map.of(999L,"B"));
        assertSame(before,RegistrationVerifiedDefaults.apply(before,List.of(parent),Map.of(13836L,REGULATION),null));
        var selected=RegistrationVerifiedDefaults.apply(draft(Map.of(999L,"A")),List.of(parent),Map.of(13836L,REGULATION),null);
        assertEquals(REGULATION,selected.attributes().get(13836L));
    }
    @Test void productionCountryDefaultsToAdvertisedRussiaIndependentlyOfVerifiedPermitFields(){
        var country=new Attribute(2630,"Страна производства","text",true,false,false,false,List.of("RU","CN"));
        var schema=List.of(country);var before=draft(Map.of());
        String resolved=RegistrationVerifiedDefaults.productionCountry(null,schema,(source,model)->{throw new AssertionError("A country default needs no source lookup");});
        var after=RegistrationVerifiedDefaults.applyProductionCountry(before,schema,resolved);
        assertEquals("RU",after.attributes().get(2630L));assertEquals("",after.attributeTypes().get(2630L));assertFalse(after.attributes().containsKey(13836L));
        assertSame(before,RegistrationVerifiedDefaults.applyProductionCountry(before,List.of(),"RU"));
        assertEquals("",RegistrationVerifiedDefaults.productionCountry(null,List.of(new Attribute(2630,"Country","text",true,false,false,false,List.of("CN"))),(source,model)->""));
    }
    @Test void explicitCountryIsCanonicalizedAndUnknownOrConflictingCountriesNeverBecomeRussia(){
        var country=new Attribute(2630,"Страна производства","text",true,false,false,false,List.of("RU","CN"));var schema=List.of(country);
        java.util.function.BiFunction<String,List<Attribute>,String> resolve=(source,model)->Map.of("Китай","CN","Россия","RU","RU","RU").getOrDefault(source,"");
        assertEquals("CN",RegistrationVerifiedDefaults.productionCountry(countrySource("Китай"),schema,resolve));
        assertEquals("",RegistrationVerifiedDefaults.productionCountry(countrySource("Unknown"),schema,resolve));
        assertEquals("",RegistrationVerifiedDefaults.productionCountry(countrySource("Китай","Россия"),schema,resolve));
        assertEquals("RU",RegistrationVerifiedDefaults.productionCountry(countrySource("Россия","RU"),schema,resolve));
        var explicit=draft(Map.of(2630L,"CN"));assertSame(explicit,RegistrationVerifiedDefaults.applyProductionCountry(explicit,schema,"RU"));
    }
    @Test void russianSizeTypeDoesNotDetermineProductionCountry(){
        var country=new Attribute(2630,"Страна производства","text",true,false,false,false,List.of("RU","CN"));
        var source=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","201"),"read","Name","","Brand","Article","1",List.of(),
                List.of(new ProductAttribute("size","Российский размер",List.of("52"),"")),List.of(),null,List.of(),Instant.now());
        assertEquals("RU",RegistrationVerifiedDefaults.productionCountry(source,List.of(country),(value,model)->{throw new AssertionError("Size must not be treated as a country");}));
    }
    @Test void conflictingExplicitCountriesRemoveTheUnreviewedFirstMapperMatchButAllowSellerOverride(){
        var country=new Attribute(2630,"Страна производства","text",true,false,false,false,List.of("RU","CN"));var schema=List.of(country);
        var source=countrySource("RU","CN");var firstMatch=draft(Map.of(2630L,"RU"));
        String unresolved=RegistrationVerifiedDefaults.productionCountry(source,schema,(value,model)->value);
        assertEquals("",unresolved);
        var automatic=RegistrationVerifiedDefaults.applyProductionCountry(firstMatch,schema,unresolved,source);
        assertFalse(automatic.attributes().containsKey(2630L));assertFalse(automatic.attributeTypes().containsKey(2630L));
        var reviewed=RegistrationReviewedValues.apply(automatic,Map.of(2630L,new RegistrationReviewedValues.Value("CN","")));
        assertEquals("CN",reviewed.attributes().get(2630L));
    }
    private ProductSnapshot countrySource(String... values){return new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","201"),"read","Name","","Brand","Article","1",List.of(),List.of(new ProductAttribute("country","Страна происхождения",List.of(values),"")),List.of(),null,List.of(),Instant.now());}
}
