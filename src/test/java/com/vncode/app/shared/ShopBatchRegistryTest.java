package com.vncode.app.shared;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopBatchRegistryTest {
    @TempDir Path root;
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",root.toString());}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private Shop shop(int id){return new Shop(id,"Fixture",Marketplace.OZON,Integer.toString(id),"synthetic");}
    @Test void differentShopsCannotOverwriteTheSameActivePdfTarget(){
        var target=root.resolve("labels.pdf");var first=ShopBatchRegistry.claim(shop(1),"ozon-print",List.of(target)).orElseThrow();
        try{assertTrue(ShopBatchRegistry.claim(shop(2),"ozon-print",List.of(root.resolve(".").resolve("labels.pdf"))).isEmpty());
            try(var other=ShopBatchRegistry.claim(shop(2),"ozon-print",List.of(root.resolve("other.pdf"))).orElseThrow()){assertEquals(2,ShopBatchRegistry.activeCount());}
        }finally{first.close();}
        try(var second=ShopBatchRegistry.claim(shop(2),"ozon-print",List.of(target)).orElseThrow()){first.close();assertTrue(ShopBatchRegistry.claim(shop(1),"ozon-print",List.of(target)).isEmpty());}
        assertFalse(ShopBatchRegistry.hasWork());
    }
    @Test void tenShopsRunIndependentlyButTheSameShopCannotQueueADuplicate(){
        var leases=new ArrayList<ShopBatchRegistry.Lease>();try{
            for(int id=1;id<=10;id++){var lease=ShopBatchRegistry.claim(shop(id),"ozon-print").orElseThrow();leases.add(lease);assertTrue(ShopBatchRegistry.hasShop(id));}
            assertEquals(10,ShopBatchRegistry.activeCount());assertTrue(ShopBatchRegistry.claim(shop(1),"ozon-print").isEmpty());
            assertThrows(IllegalStateException.class,()->leases.getFirst().requireOwner(shop(2)));
        }finally{leases.forEach(ShopBatchRegistry.Lease::close);}
        assertFalse(ShopBatchRegistry.hasWork());
    }
    @Test void aDifferentLocalUsersDataDoesNotShareTheBatchNamespace(){
        var first=ShopBatchRegistry.claim(shop(1),"ozon-print").orElseThrow();
        try{System.setProperty("vncode.appdata.dir",root.resolve("second-user").toString());assertFalse(ShopBatchRegistry.hasShop(1));
            try(var second=ShopBatchRegistry.claim(shop(1),"ozon-print").orElseThrow()){assertTrue(ShopBatchRegistry.hasShop(1));}
        }finally{first.close();}
        assertFalse(ShopBatchRegistry.hasWork());
    }
    @Test void racingClientsObtainExactlyOneLeaseAndReleaseDoesNotEraseAnotherJob()throws Exception{
        var lease=ShopBatchRegistry.claim(shop(1),"ozon-pack").orElseThrow();
        try(var pool=Executors.newFixedThreadPool(8)){
            var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<100;n++)futures.add(pool.submit(()->ShopBatchRegistry.claim(shop(1),"ozon-pack").isPresent()));
            for(var future:futures)assertFalse(future.get());lease.close();lease.close();
            try(var next=ShopBatchRegistry.claim(shop(1),"ozon-pack").orElseThrow()){assertTrue(ShopBatchRegistry.hasShop(1));lease.close();assertTrue(ShopBatchRegistry.hasShop(1));}
        }finally{lease.close();}
        assertFalse(ShopBatchRegistry.hasWork());
    }
}
