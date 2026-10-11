package com.vncode.app.ui.products;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProductSelectionTest {
    private ProductKey key(int shop,int id){return new ProductKey(shop,Marketplace.WILDBERRIES,""+id,"");}
    @Test void selectionSurvivesFilterButClearsOnShopChange(){
        var selection=new ProductSelection();selection.switchShop(1,Marketplace.WILDBERRIES);
        selection.selectPage(List.of(key(1,1),key(1,2)));selection.selectPage(List.of(key(1,3)));
        assertEquals(3,selection.freeze().size());selection.switchShop(1,Marketplace.WILDBERRIES);assertEquals(3,selection.freeze().size());
        selection.switchShop(2,Marketplace.WILDBERRIES);assertTrue(selection.freeze().isEmpty());
        assertThrows(SecurityException.class,()->selection.selectPage(List.of(key(1,7))));
    }
    @Test void explicitAllResultsIsFrozen(){
        var selection=new ProductSelection();selection.switchShop(1,Marketplace.WILDBERRIES);
        var keys=new ArrayList<ProductKey>();for(int i=0;i<251;i++)keys.add(key(1,i));selection.selectPage(keys);
        var frozen=selection.freeze();selection.selectPage(List.of(key(1,252)));assertEquals(251,frozen.size());
        assertThrows(UnsupportedOperationException.class,()->frozen.clear());
    }
}
