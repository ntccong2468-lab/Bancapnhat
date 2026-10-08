package com.vncode.app.features.news;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class NewsServiceTest {
 @TempDir Path root;
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
 @Test void rejectsDuplicateIdsAndUnsafeLinksNeverBecomeClickable() {
  String json="{\"items\":[{\"id\":\"1\",\"publishedAt\":\"2026-10-08T00:00:00Z\",\"title\":\"x\",\"body\":\"<script>x</script>\"},{\"id\":\"1\",\"publishedAt\":\"2026-10-08T00:00:00Z\",\"title\":\"y\",\"body\":\"x\"}]}";
  assertThrows(IllegalArgumentException.class,()->NewsService.parse(json));
  assertFalse(NewsService.safeLink("javascript:alert(1)")); assertFalse(NewsService.safeLink("file:///etc/passwd"));
  assertTrue(NewsService.safeLink("https://github.com/ntccong2468-lab/Bancapnhat"));
 }
}
