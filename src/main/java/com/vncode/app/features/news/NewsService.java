package com.vncode.app.features.news;
import com.google.gson.JsonParser;
import okhttp3.*;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class NewsService {
 public record Page(List<NewsItem> items,String nextCursor) {}
 private volatile List<NewsItem> items;
 private final NewsRepository repository;
 public NewsService(List<NewsItem> rows,NewsRepository repo) {
  repository=repo;items=rows.stream().sorted(Comparator.comparing(NewsItem::publishedAt).reversed().thenComparing(NewsItem::id)).toList();
 }
 public static List<NewsItem> parse(String json) {
  if(json.length()>1_000_000)throw new IllegalArgumentException("News feed too large");
  var rows=JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("items");
  if(rows==null||rows.size()>1000)throw new IllegalArgumentException("Invalid news feed");
  List<NewsItem> out=new ArrayList<>();Set<String> ids=new HashSet<>();
  for(var row:rows) {var obj=row.getAsJsonObject();String id=obj.get("id").getAsString(),title=obj.get("title").getAsString(),body=obj.get("body").getAsString();
   if(id.isBlank()||id.length()>120||!ids.add(id)||title.isBlank()||title.length()>300||body.length()>50_000)throw new IllegalArgumentException("Invalid news item");
   out.add(new NewsItem(id,Instant.parse(obj.get("publishedAt").getAsString()),title,body)); }
  return List.copyOf(out);
 }
 public void refresh() throws IOException {
  var client=new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build();
  var request=new Request.Builder().url("https://raw.githubusercontent.com/ntccong2468-lab/Bancapnhat/HEAD/news/feed.json").build();
  try(var response=client.newCall(request).execute()) {
   if(!response.isSuccessful()||response.body()==null)throw new IOException("NEWS_HTTP_"+response.code());
   try(var input=response.body().byteStream()) { byte[] bytes=input.readNBytes(1_000_001);if(bytes.length>1_000_000)throw new IOException("NEWS_SIZE");
    String json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);List<NewsItem> parsed=parse(json);
    repository.cache(json); items=parsed.stream().sorted(Comparator.comparing(NewsItem::publishedAt).reversed().thenComparing(NewsItem::id)).toList(); }
  } catch(IOException|RuntimeException e) {
   try {items=parse(repository.cached()).stream().sorted(Comparator.comparing(NewsItem::publishedAt).reversed().thenComparing(NewsItem::id)).toList();}catch(IOException|RuntimeException cacheFailure){throw new IOException("NEWS_UNAVAILABLE",e);}
  }
 }
 public Page loadPage(String cursor) {
  List<NewsItem> snapshot=items;int start=cursor==null?0:Integer.parseInt(cursor);if(start<0||start>snapshot.size())throw new IllegalArgumentException("Invalid news cursor");
  int end=Math.min(start+20,snapshot.size());return new Page(List.copyOf(snapshot.subList(start,end)),end<snapshot.size()?Integer.toString(end):null);
 }
 public int unreadCount() {return (int)items.stream().filter(item->!repository.isRead(item.id())).count();}
 public boolean isRead(String id) {return repository.isRead(id);}
 public void markRead(String id)throws IOException {repository.markRead(id);}
 public static boolean safeLink(String url) {try{URI uri=URI.create(url);return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null&&uri.getUserInfo()==null;}catch(RuntimeException e){return false;}}
}
