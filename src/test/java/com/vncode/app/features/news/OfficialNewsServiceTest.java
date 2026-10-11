package com.vncode.app.features.news;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.wb.WbNewsClient;
import com.vncode.app.integration.marketplace.Marketplace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class OfficialNewsServiceTest {
 @TempDir Path directory;
 private final Shop shop=new Shop(1,"Fixture","fixture-token");
 private SellerNews news(String id,String title){return SellerNews.wb(NewsScope.forShop(shop),id,Instant.parse("2026-10-11T00:00:00Z"),title,"Body",URI.create("https://seller.wildberries.ru/"));}
 @Test void sameIdNewRevisionBecomesUnread(){var repo=new SellerNewsRepository(directory.resolve("news.db"));var scope=NewsScope.forShop(shop);var first=news("1","First");repo.saveBatch(scope,List.of(first),Instant.now());repo.markRead(scope,"1",first.revision());assertTrue(repo.isRead(first));var second=news("1","Revised");repo.saveBatch(scope,List.of(second),Instant.now());assertFalse(repo.isRead(second));assertEquals("Revised",repo.cached(scope).getFirst().originalTitle());}
 @Test void scopeDoesNotExposeOtherShopOrChangedToken(){var repo=new SellerNewsRepository(directory.resolve("news.db"));var scope=NewsScope.forShop(shop);repo.saveBatch(scope,List.of(news("1","Title")),Instant.now());assertTrue(repo.cached(NewsScope.forShop(new Shop(2,"B","other-token"))).isEmpty());assertTrue(repo.cached(NewsScope.forShop(new Shop(1,"A","rotated-token"))).isEmpty());assertThrows(SecurityException.class,()->repo.saveBatch(scope,List.of(SellerNews.wb(NewsScope.forShop(new Shop(2,"B","other")),"1",Instant.EPOCH,"Title","Body",URI.create("https://seller.wildberries.ru/"))),Instant.now()));}
 @Test void backgroundRefreshHonorsThirtyMinutes()throws Exception{var calls=new AtomicInteger();var repo=new SellerNewsRepository(directory.resolve("news.db"));var clock=Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"),ZoneOffset.UTC);var service=new OfficialNewsService(repo,(owner,from,id)->{calls.incrementAndGet();return new WbNewsClient.NewsBatch(List.of(news("1","Title")),"1");},clock);service.refresh(shop,NewsScope.forShop(shop),false);service.refresh(shop,NewsScope.forShop(shop),false);assertEquals(1,calls.get());}
 @Test void officialBaseTokenBudgetResumesCursorAfterOneHour()throws Exception {
  var calls=new AtomicInteger();var file=directory.resolve("news.db");var repo=new SellerNewsRepository(file);var firstTime=Instant.parse("2026-10-11T10:00:00Z");
  var reader=new OfficialNewsService.Reader(){public Duration minimumInterval(){return Duration.ofHours(1);}public WbNewsClient.NewsBatch readSince(Shop owner,Instant from,String id){calls.incrementAndGet();if(id.isBlank()){var rows=new ArrayList<SellerNews>();for(int i=1;i<=100;i++)rows.add(news(""+i,"Title"));return new WbNewsClient.NewsBatch(rows,"100");}assertEquals("100",id);return new WbNewsClient.NewsBatch(List.of(news("100","Title"),news("101","Title")),"101");}};
  assertEquals(100,new OfficialNewsService(repo,reader,Clock.fixed(firstTime,ZoneOffset.UTC)).refresh(shop,NewsScope.forShop(shop),true).size());
  assertEquals(100,new OfficialNewsService(new SellerNewsRepository(file),reader,Clock.fixed(firstTime.plusSeconds(1801),ZoneOffset.UTC)).refresh(shop,NewsScope.forShop(shop),true).size());assertEquals(1,calls.get());
  assertEquals(101,new OfficialNewsService(new SellerNewsRepository(file),reader,Clock.fixed(firstTime.plusSeconds(3601),ZoneOffset.UTC)).refresh(shop,NewsScope.forShop(shop),true).size());assertEquals(2,calls.get());
 }
 @Test void inclusiveCursorDoesNotRepeatNews()throws Exception{var calls=new AtomicInteger();var service=new OfficialNewsService(new SellerNewsRepository(directory.resolve("news.db")),(owner,from,id)->{calls.incrementAndGet();var rows=new ArrayList<SellerNews>();if(id.isBlank()){for(int i=1;i<=100;i++)rows.add(news(""+i,"Title"));return new WbNewsClient.NewsBatch(rows,"100");}return new WbNewsClient.NewsBatch(List.of(news("100","Title"),news("101","Title")),"101");},Clock.systemUTC());assertEquals(101,service.refresh(shop,NewsScope.forShop(shop),false).size());assertEquals(2,calls.get());}
 @Test void rateLimitHonorsRetryAfterAndOzonDoesNotCallWb()throws Exception{var calls=new AtomicInteger();var service=new OfficialNewsService(new SellerNewsRepository(directory.resolve("news.db")),(owner,from,id)->{calls.incrementAndGet();throw new WbNewsClient.RateLimitedException(Duration.ofMinutes(3));},Clock.systemUTC());assertThrows(java.io.IOException.class,()->service.refresh(shop,NewsScope.forShop(shop),true));assertThrows(java.io.IOException.class,()->service.refresh(shop,NewsScope.forShop(shop),true));assertEquals(1,calls.get());var ozon=new Shop(2,"O",Marketplace.OZON,"123","fixture");assertThrows(SecurityException.class,()->service.refresh(ozon,NewsScope.forShop(ozon),true));assertEquals(1,calls.get());}
}
