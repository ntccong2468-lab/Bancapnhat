package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationStorageConcurrencyTest {
    @TempDir Path directory;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,100,'fixture','Fixture','2026-11-07T00:00:00Z')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,nm_id,chrt_id,tech_size) VALUES(1,100,101,'XL')");
        }
    }
    @AfterEach void close(){System.clearProperty("vncode.appdata.dir");}

    @Test void firstRegistrationWaitsForTheLocalWriteQueueInsteadOfFailingStorageBusy()throws Exception {
        var repository=new ZnackCardRegistrationRepository();var sku=repository.find(1,101);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){
            var first=workers.submit(()->SqliteWriteTransaction.run(c->{
                try(var s=c.createStatement()){s.execute("INSERT INTO app_config(key,value) VALUES('fixture-write','pending')");}
                entered.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("fixture writer timeout");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new SQLException(error);}
                return true;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var claim=workers.submit(()->{attempted.countDown();return repository.claim(1,sku,ZnackCardRegistrationModels.RegistrationAction.REGISTER);});
            try{
                assertTrue(attempted.await(2,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->claim.get(5500,TimeUnit.MILLISECONDS),"The queued claim must wait, not fail after an independent SQLite timeout");
            }finally{release.countDown();}
            assertTrue(first.get(5,TimeUnit.SECONDS));assertTrue(claim.get(5,TimeUnit.SECONDS));
        }
        assertEquals(ZnackCardRegistrationModels.Status.CHECKING,repository.find(1,101).status());
    }
}
