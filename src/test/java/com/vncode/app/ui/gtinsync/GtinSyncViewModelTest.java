package com.vncode.app.ui.gtinsync;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinSyncViewModelTest {
    final ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"101","11");
    final ProductSnapshot product=new ProductSnapshot(key,"PANTS","Đen","XL",List.of("other"),"fp",Instant.now());
    final RegisteredGtin gtin=new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now());
    @Test void rejectsOldResponseAfterSwitchingShop() {
        var vm=new GtinSyncViewModel();vm.load(new Shop(1,"A","test"));var old=vm.token();
        vm.load(new Shop(2,"B","test"));assertFalse(vm.accept(old,List.of(gtin),List.of(product)));assertTrue(vm.rows().isEmpty());
    }
    @Test void exactMatchSuggestsAddButNeverReplacement() {
        var vm=new GtinSyncViewModel();vm.load(new Shop(1,"A","test"));
        assertTrue(vm.accept(vm.token(),List.of(gtin),List.of(product)));
        var row=vm.rows().getFirst();assertEquals("00000000000017",row.gtin());assertEquals(Operation.ADD,row.operation());assertEquals("",row.oldGtin());
        assertEquals(List.of(key),vm.exactKeys());
    }
    @Test void selectingInternalMappingDoesNotCreateJobs() {
        var vm=new GtinSyncViewModel();vm.load(new Shop(1,"A","test"));vm.accept(vm.token(),List.of(gtin),List.of(product));
        vm.choose(key,gtin.gtin(),Operation.REPLACE,"other");assertEquals("other",vm.rows().getFirst().oldGtin());
        assertThrows(IllegalArgumentException.class,()->vm.choose(key,"00000000000024",Operation.ADD,""));
    }
}
