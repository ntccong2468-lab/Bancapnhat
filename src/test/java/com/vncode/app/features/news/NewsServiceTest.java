package com.vncode.app.features.news;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class NewsServiceTest {
 @TempDir Path root;
 @Test void backgroundReadAcknowledgementCannotBlockShopSwitch()throws Exception {
  var path=root.resolve("news.sqlite");var repository=new SellerNewsRepository(path);var scope=NewsScope.forShop(new com.vncode.app.models.Shop(1,"Fixture","fixture"));var item=SellerNews.wb(scope,"1",Instant.EPOCH,"Official","Body",java.net.URI.create("https://seller.wildberries.ru/news/1"));repository.saveBatch(scope,List.of(item),Instant.now());
  var service=new NewsService(List.of(),new NewsRepository(root));service.setOfficial(List.of(item),new OfficialNewsService(repository,(s,f,id)->{throw new java.io.IOException();},java.time.Clock.systemUTC()));
  try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);var lock=connection.createStatement();var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   lock.execute("PRAGMA journal_mode=DELETE");lock.execute("BEGIN EXCLUSIVE");var entered=new java.util.concurrent.CountDownLatch(1);var write=pool.submit(()->{entered.countDown();service.markRead(item);});entered.await();Thread.sleep(60);
   try{pool.submit(()->service.setOfficial(List.of(),new OfficialNewsService(repository,(s,f,id)->{throw new java.io.IOException();},java.time.Clock.systemUTC()))).get(300,java.util.concurrent.TimeUnit.MILLISECONDS);}finally{lock.execute("ROLLBACK");write.get(5,java.util.concurrent.TimeUnit.SECONDS);}
  }
 }
 @Test void queuedReadKeepsCapturedOwnerAfterSwitch()throws Exception {
  var repository=new SellerNewsRepository(root.resolve("news.sqlite"));var scope=NewsScope.forShop(new com.vncode.app.models.Shop(1,"Fixture","fixture"));
  var item=SellerNews.wb(scope,"1",Instant.EPOCH,"Official","Body",java.net.URI.create("https://seller.wildberries.ru/news/1"));repository.saveBatch(scope,List.of(item),Instant.now());
  var service=new NewsService(List.of(),new NewsRepository(root));service.setOfficial(List.of(item),new OfficialNewsService(repository,(s,f,id)->{throw new java.io.IOException();},java.time.Clock.systemUTC()));
  service.setOfficial(List.of(),new OfficialNewsService(repository,(s,f,id)->{throw new java.io.IOException();},java.time.Clock.systemUTC()));service.markRead(item);
  assertTrue(repository.cached(scope).getFirst().read());assertFalse(new NewsRepository(root).isRead(item.displayId()));assertEquals(0,service.unreadCount());
 }
 @Test void cachedNewsReadStateDoesNotOpenSqliteWhileRendering()throws Exception {
  var path=root.resolve("official.sqlite");var repository=new SellerNewsRepository(path);var owner=NewsScope.forShop(new com.vncode.app.models.Shop(1,"Fixture","fixture"));
  var item=SellerNews.wb(owner,"1",Instant.EPOCH,"Official","Body",java.net.URI.create("https://seller.wildberries.ru/news/1"));repository.saveBatch(owner,List.of(item),Instant.now());repository.markRead(owner,"1",item.revision());
  var service=new NewsService(List.of(),new NewsRepository(root));service.setOfficial(repository.cached(owner),new OfficialNewsService(repository,(s,f,id)->{throw new java.io.IOException();},java.time.Clock.systemUTC()));
  java.nio.file.Files.move(path,root.resolve("offline.sqlite"));assertEquals(0,service.unreadCount());assertTrue(service.isRead(item.displayId()));
 }
 @Test void readStateSurvivesRestartAndIsBoundToStableId() throws Exception {
  NewsRepository repo=new NewsRepository(root);
  assertFalse(repo.isRead("one")); repo.markRead("one");
  assertTrue(new NewsRepository(root).isRead("one")); assertFalse(repo.isRead("two"));
 }
 @Test void pagesDoNotRepeatAndEmptyCursorEnds() {
  List<NewsItem> rows=java.util.stream.IntStream.range(0,25).mapToObj(i->new NewsItem("id"+i,Instant.EPOCH.plusSeconds(i),"Title "+i,"**body**")).toList();
  NewsService service=new NewsService(rows,new NewsRepository(root));
  var first=service.loadPage(null); assertEquals(20,first.items().size());
  var last=service.loadPage(first.nextCursor()); assertEquals(5,last.items().size()); assertNull(last.nextCursor());
  assertEquals(25,service.unreadCount());
 }
 @Test void vnFeedIsNeverLabeledOfficialWb(){var service=new NewsService(List.of(new NewsItem("vn-1",Instant.EPOCH,"VN title","VN body")),new NewsRepository(root));assertTrue(service.sellerNews("vn-1").isEmpty());}
 @Test void rejectsDuplicateIdsAndUnsafeLinksNeverBecomeClickable() {
  String json="{\"items\":[{\"id\":\"1\",\"publishedAt\":\"2026-10-08T00:00:00Z\",\"title\":\"x\",\"body\":\"<script>x</script>\"},{\"id\":\"1\",\"publishedAt\":\"2026-10-08T00:00:00Z\",\"title\":\"y\",\"body\":\"x\"}]}";
  assertThrows(IllegalArgumentException.class,()->NewsService.parse(json));
  assertFalse(NewsService.safeLink("javascript:alert(1)")); assertFalse(NewsService.safeLink("file:///etc/passwd"));
  assertTrue(NewsService.safeLink("https://github.com/ntccong2468-lab/Bancapnhat"));
 }
}
