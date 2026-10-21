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
 private volatile List<NewsItem> ownItems;
 private volatile Map<String,SellerNews> official=Map.of();
 private volatile OfficialNewsService officialService;
 private final NewsRepository repository;
 public NewsService(List<NewsItem> rows,NewsRepository repo) {
  repository=repo;ownItems=List.copyOf(rows);rebuild();
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
    repository.cache(json); replaceOwn(parsed); }
  } catch(IOException|RuntimeException e) {
   try {replaceOwn(parse(repository.cached()));}catch(IOException|RuntimeException cacheFailure){throw new IOException("NEWS_UNAVAILABLE",e);}
  }
 }
 public Page loadPage(String cursor) {
  List<NewsItem> snapshot=items;int start=cursor==null?0:Integer.parseInt(cursor);if(start<0||start>snapshot.size())throw new IllegalArgumentException("Invalid news cursor");
  int end=Math.min(start+20,snapshot.size());return new Page(List.copyOf(snapshot.subList(start,end)),end<snapshot.size()?Integer.toString(end):null);
 }
 public int unreadCount() {return (int)items.stream().filter(item->!isRead(item.id())).count();}
 public boolean isRead(String id) {var item=official.get(id);return item==null?repository.isRead(id):item.read();}
 public void markRead(String id)throws IOException {var item=official.get(id);if(item==null)repository.markRead(id);else markRead(item);}
 /** A queued read acknowledgement keeps the exact scope/revision captured when clicked. */
 public void markRead(SellerNews item) {
  officialService.markRead(item);
  synchronized(this){
  var current=official.get(item.displayId());
  if(current!=null&&current.revision().equals(item.revision())){var updated=new LinkedHashMap<>(official);updated.put(item.displayId(),new SellerNews(item.source(),item.sourceId(),item.revision(),item.scope(),item.publishedAt(),item.originalTitle(),item.originalBody(),item.sourceURL(),true));official=Map.copyOf(updated);}
  }
 }
 public Optional<SellerNews> sellerNews(String id){return Optional.ofNullable(official.get(id));}
 public synchronized void setOfficial(List<SellerNews> rows,OfficialNewsService provider){officialService=provider;var map=new LinkedHashMap<String,SellerNews>();for(var row:rows)map.put(row.displayId(),row);official=Map.copyOf(map);rebuild();}
 private synchronized void replaceOwn(List<NewsItem> rows){ownItems=List.copyOf(rows);rebuild();}
 private void rebuild(){var combined=new ArrayList<>(ownItems);for(var item:official.values())combined.add(new NewsItem(item.displayId(),item.publishedAt(),item.originalTitle(),item.originalBody()));items=combined.stream().sorted(Comparator.comparing(NewsItem::publishedAt).reversed().thenComparing(NewsItem::id)).toList();}
 public static boolean safeLink(String url) {try{URI uri=URI.create(url);return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null&&uri.getUserInfo()==null;}catch(RuntimeException e){return false;}}
}
