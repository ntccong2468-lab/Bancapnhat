package com.vncode.app.features.gs1;
import com.vncode.app.integration.znack.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.net.URI;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class Gs1BalanceRepositoryTest {
 @TempDir Path directory;
 @Test void balanceCacheScopeIsolation(){var repo=new Gs1BalanceRepository(directory.resolve("balance.db"));var scope=new BalanceScope("7707083893","production","2","3");repo.save(new BalanceSnapshot(scope,new BigDecimal("0.00"),"RUB",Instant.now(),URI.create("https://markirovka.crpt.ru/bff-elk/v1/organisation/balance")));assertEquals(new BigDecimal("0.00"),repo.load(scope).orElseThrow().amount());assertTrue(repo.load(new BalanceScope(scope.inn(),"sandbox","2","3")).isEmpty());assertTrue(repo.load(new BalanceScope(scope.inn(),"production","4","3")).isEmpty());assertTrue(repo.load(new BalanceScope("7811089030","production","2","3")).isEmpty());assertEquals(1,new Gs1BalanceRepository(directory.resolve("balance.db")).list(scope.inn(),scope.environment()).size());}
}
