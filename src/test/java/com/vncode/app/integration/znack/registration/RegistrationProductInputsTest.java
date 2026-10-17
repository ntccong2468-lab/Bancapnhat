package com.vncode.app.integration.znack.registration;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RegistrationProductInputsTest {
    @Test void registrationUsesReadbackAfterBulkEditRatherThanStaleProductCache(){
        var selected=new Sku(101,201,1,"old article","old category","old brand","old title","Blue","M",List.of(),"",true,null,null,null,Status.NOT_CREATED,null,false,"48","checkpoint");
        var snapshot=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","201"),"live:new","Bộ nâu","","New brand","152DASH-52","1",List.of(),
                List.of(new ProductAttribute("3","Состав",List.of("хлопок 100%"),null),new ProductAttribute("4","ТНВЭД",List.of("6112110000"),null)),
                List.of(new ProductVariant("201","152DASH-52","Brown","L","",false,"52")),null,List.of(),Instant.now(),Map.of("source.category.name","Костюмы спортивные"));
        var actual=RegistrationProductInputs.from(selected,snapshot);
        assertEquals("New brand",actual.sku().brand());assertEquals("Brown",actual.sku().color());assertEquals("52",actual.sku().wbSize());assertEquals("152DASH-52",actual.sku().vendorCode());assertEquals("checkpoint",actual.sku().registrationRevision());
        assertEquals("6112110000",ZnackWbAttributeMapper.findTnved(actual.characteristics()));assertTrue(actual.characteristics().stream().anyMatch(c->c.joinedValue().equals("хлопок 100%")));
        var foreign=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"102","201"),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),snapshot.checkedAt(),snapshot.metadata());
        assertThrows(SecurityException.class,()->RegistrationProductInputs.from(selected,foreign));
    }
}
