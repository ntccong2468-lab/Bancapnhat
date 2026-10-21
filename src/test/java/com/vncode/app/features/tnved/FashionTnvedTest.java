package com.vncode.app.features.tnved;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FashionTnvedTest {
    @org.junit.jupiter.api.Test void offersSpecificSyntheticAndArtificialFiberFilters(){
        var catalog=new FashionTnvedCatalog();
        org.junit.jupiter.api.Assertions.assertTrue(catalog.materials().containsAll(java.util.List.of("Полиэстер","Вискоза","Полиамид","Акрил")));
        for(String material:java.util.List.of("Полиэстер","Полиамид","Акрил"))org.junit.jupiter.api.Assertions.assertEquals(catalog.search("",java.util.Set.of(),"","Синтетика",""),catalog.search("",java.util.Set.of(),"",material,""));
        org.junit.jupiter.api.Assertions.assertEquals(catalog.search("",java.util.Set.of(),"","Искусственные нити",""),catalog.search("",java.util.Set.of(),"","Вискоза",""));
    }

    ProductSnapshot product(String material,String gender,String fabric){
        return new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"10","11"),"cache:one","Костюм спортивный мужской","","Brand","own","9",List.of(),
                List.of(new ProductAttribute("1","Состав",List.of(material),""),new ProductAttribute("2","Пол",List.of(gender),""),new ProductAttribute("3","Тип ткани",List.of(fabric),"")),List.of(),null,List.of(),Instant.now());
    }
    @Test void referenceHas526DistinctCodesAndTheSourceFiltersRemainUsable(){
        var catalog=new FashionTnvedCatalog();assertEquals(526,catalog.entries().size());assertEquals(526,catalog.entries().stream().map(FashionTnvedCatalog.Entry::code).distinct().count());assertTrue(catalog.entries().stream().allMatch(e->e.code().matches("[0-9]{10}")));
        assertTrue(catalog.search("611211",Set.of("Костюмы спортивные"),"Мужской","Хлопок","Трикотаж").stream().anyMatch(e->e.code().equals("6112110000")));
        assertTrue(catalog.search("",Set.of("Костюмы спортивные"),"Мужской","Синтетика","Ткань").stream().anyMatch(e->e.code().startsWith("621133")));
    }
    @Test void explicitFabricAndDominantCompositionProduceDifferentCodesPerProduct(){
        var advisor=new ProductTnvedAdvisor();var cotton=advisor.propose(product("хлопок 90%; полиэстер 10%","Мужской","трикотаж"));
        assertTrue(cotton.unique(),cotton.toString());assertEquals("6112110000",cotton.candidates().getFirst().code());
        var synthetic=advisor.propose(product("полиэстер 95%, эластан 5%","Мужской","трикотаж"));assertTrue(synthetic.unique());assertEquals("6112120000",synthetic.candidates().getFirst().code());
    }
    @Test void mixedUnknownCompositionAndMissingFactsNeverAutoSelect(){
        var advisor=new ProductTnvedAdvisor();for(String material:List.of("хлопок, полиэстер","хлопок 50%, полиэстер 50%","хлопок 20%, бамбук 80%"))assertFalse(advisor.propose(product(material,"Мужской","Трикотаж")).unique(),material);
        assertFalse(advisor.propose(product("хлопок","","Трикотаж")).unique());assertFalse(advisor.propose(product("хлопок","Мужской","")).unique());
        var woven=advisor.propose(product("хлопок","Мужской","Ткань"));assertFalse(woven.unique());assertTrue(woven.candidates().size()>1);
    }
    @Test void invalidPercentagesCannotCreateAnAutomaticProposal(){
        assertEquals("",ProductTnvedAdvisor.dominantMaterial("хлопок 90%, полиэстер 90%"));assertEquals("",ProductTnvedAdvisor.dominantMaterial("хлопок 20%, бамбук 80%"));assertEquals("Хлопок",ProductTnvedAdvisor.dominantMaterial("хлопок 80%, бамбук 20%"));
    }
}
