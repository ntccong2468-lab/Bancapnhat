package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.*;
import com.vncode.app.shared.MarketplaceRequestBudget;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizServiceScopeTest {
    @TempDir Path directory;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'WB shop','fixture-token')");}
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void credentialChangeDuringGlobalBudgetWaitPreventsTheActualWrite()throws Exception{
        var calls=new AtomicInteger();var entered=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try(var first=MarketplaceRequestBudget.acquire();var second=MarketplaceRequestBudget.acquire()){
            Future<Integer> waiting=executor.submit(()->{entered.countDown();try(var slot=MarketplaceRequestBudget.acquire()){return KizService.withWriteScope(1,"fixture-token",calls::incrementAndGet);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try(Connection c=Database.getConnection();Statement st=c.createStatement()){st.execute("UPDATE shops SET api_key='fixture-new-token' WHERE id=1");}
            first.close();var failure=assertThrows(ExecutionException.class,()->waiting.get(5,TimeUnit.SECONDS));assertInstanceOf(IOException.class,failure.getCause());assertEquals("SHOP_CREDENTIALS_CHANGED",failure.getCause().getMessage());assertEquals(0,calls.get());
        } finally {executor.shutdownNow();}
    }
    @Test void revocationDuringGlobalBudgetWaitRechecksRightsBeforeActualWrite()throws Exception{
        var prior=LicenseRuntime.service();var calls=new AtomicInteger();var entered=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try(var denied=LicenseDomainFixture.denied(directory.resolve("denied"));var first=MarketplaceRequestBudget.acquire();var second=MarketplaceRequestBudget.acquire()){
            Future<Integer> waiting=executor.submit(()->{entered.countDown();try(var slot=MarketplaceRequestBudget.acquire()){return KizService.withWriteScope(1,"fixture-token",calls::incrementAndGet);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));LicenseRuntime.install(denied);first.close();
            var failure=assertThrows(ExecutionException.class,()->waiting.get(5,TimeUnit.SECONDS));assertInstanceOf(LicenseGate.LicenseDeniedException.class,failure.getCause());assertEquals(0,calls.get());
        } finally {LicenseRuntime.install(prior);executor.shutdownNow();}
    }
    @Test void cancellationDuringGlobalBudgetWaitPreventsActualWrite()throws Exception{
        var calls=new AtomicInteger();var cancelled=new AtomicBoolean();var entered=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try(var first=MarketplaceRequestBudget.acquire();var second=MarketplaceRequestBudget.acquire()){
            Future<Integer> waiting=executor.submit(()->{entered.countDown();try(var slot=MarketplaceRequestBudget.acquire()){return KizService.withWriteScope(1,"fixture-token",cancelled::get,calls::incrementAndGet);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));cancelled.set(true);first.close();
            var failure=assertThrows(ExecutionException.class,()->waiting.get(5,TimeUnit.SECONDS));assertEquals("KIZ_REQUEST_CANCELLED",failure.getCause().getMessage());assertEquals(0,calls.get());
        } finally {executor.shutdownNow();}
    }
}
