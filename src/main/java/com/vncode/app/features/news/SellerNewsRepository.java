package com.vncode.app.features.news;
import com.vncode.app.config.Database;
import java.nio.file.Path;
import java.sql.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
public final class SellerNewsRepository {
 @FunctionalInterface private interface Connections {Connection open()throws SQLException;}
 private final Connections connections;
 public SellerNewsRepository(){connections=Database::getConnection;init();}
 public SellerNewsRepository(Path path){connections=()->DriverManager.getConnection("jdbc:sqlite:"+path);init();}
 private void init(){try(var c=connections.open()){SellerNewsSchema.ensureTables(c);}catch(SQLException error){throw unavailable();}}
 public List<SellerNews> cached(NewsScope scope){try(var c=connections.open();var p=c.prepareStatement("SELECT * FROM seller_news WHERE shop_id=? AND marketplace=? AND owner=? ORDER BY published_at DESC,source_id DESC LIMIT 1000")){bind(p,scope);try(var r=p.executeQuery()){var out=new ArrayList<SellerNews>();while(r.next())try{out.add(new SellerNews(r.getString("source"),r.getString("source_id"),r.getString("revision"),scope,Instant.parse(r.getString("published_at")),r.getString("title"),r.getString("body"),URI.create(r.getString("source_url")),r.getString("revision").equals(r.getString("read_revision"))));}catch(RuntimeException corrupt){}return List.copyOf(out);}}catch(SQLException failure){throw unavailable();}}
 public void saveBatch(NewsScope scope,List<SellerNews> items,Instant checked){saveBatch(scope,items,checked,"");}
 public void saveBatch(NewsScope scope,List<SellerNews> items,Instant checked,String cursor){for(var item:items)if(!scope.equals(item.scope()))throw new SecurityException("News snapshot owner mismatch");try(var c=connections.open()){c.setAutoCommit(false);try(var p=c.prepareStatement("INSERT INTO seller_news(shop_id,marketplace,owner,source,source_id,revision,published_at,title,body,source_url) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,owner,source,source_id) DO UPDATE SET revision=excluded.revision,published_at=excluded.published_at,title=excluded.title,body=excluded.body,source_url=excluded.source_url")){
   for(var item:items){bind(p,scope);p.setString(4,item.source());p.setString(5,item.sourceId());p.setString(6,item.revision());p.setString(7,item.publishedAt().toString());p.setString(8,item.originalTitle());p.setString(9,item.originalBody());p.setString(10,item.sourceURL().toString());p.addBatch();}p.executeBatch();}
   try(var p=c.prepareStatement("INSERT INTO seller_news_refresh(shop_id,marketplace,owner,checked_at,cursor) VALUES(?,?,?,?,?) ON CONFLICT(shop_id,marketplace,owner) DO UPDATE SET checked_at=excluded.checked_at,retry_at=NULL,cursor=excluded.cursor")){bind(p,scope);p.setString(4,checked.toString());p.setString(5,cursor);p.executeUpdate();}
   try(var p=c.prepareStatement("DELETE FROM seller_news WHERE shop_id=? AND marketplace=? AND owner=? AND rowid NOT IN (SELECT rowid FROM seller_news WHERE shop_id=? AND marketplace=? AND owner=? ORDER BY published_at DESC,source_id DESC LIMIT 1000)")){bind(p,scope);p.setInt(4,scope.shopId());p.setString(5,scope.marketplace().name());p.setString(6,scope.ownerFingerprint());p.executeUpdate();}c.commit();
  }catch(SQLException failure){throw unavailable();}}
 public void markRead(NewsScope scope,String id,String revision){try(var c=connections.open();var p=c.prepareStatement("UPDATE seller_news SET read_revision=? WHERE shop_id=? AND marketplace=? AND owner=? AND source_id=? AND revision=?")){p.setString(1,revision);p.setInt(2,scope.shopId());p.setString(3,scope.marketplace().name());p.setString(4,scope.ownerFingerprint());p.setString(5,id);p.setString(6,revision);p.executeUpdate();}catch(SQLException failure){throw unavailable();}}
 public boolean isRead(SellerNews item){try(var c=connections.open();var p=c.prepareStatement("SELECT read_revision FROM seller_news WHERE shop_id=? AND marketplace=? AND owner=? AND source=? AND source_id=?")){bind(p,item.scope());p.setString(4,item.source());p.setString(5,item.sourceId());try(var r=p.executeQuery()){return r.next()&&item.revision().equals(r.getString(1));}}catch(SQLException failure){throw unavailable();}}
 public String cursor(NewsScope scope){try(var c=connections.open();var p=c.prepareStatement("SELECT cursor FROM seller_news_refresh WHERE shop_id=? AND marketplace=? AND owner=?")){bind(p,scope);try(var r=p.executeQuery()){return r.next()?Objects.toString(r.getString(1),""):"";}}catch(SQLException failure){throw unavailable();}}
 public Optional<Instant> checkedAt(NewsScope scope){return time(scope,"checked_at");}
 public Optional<Instant> retryAt(NewsScope scope){return time(scope,"retry_at");}
 private Optional<Instant> time(NewsScope scope,String column){try(var c=connections.open();var p=c.prepareStatement("SELECT "+column+" FROM seller_news_refresh WHERE shop_id=? AND marketplace=? AND owner=?")){bind(p,scope);try(var r=p.executeQuery()){if(!r.next()||r.getString(1)==null)return Optional.empty();return Optional.of(Instant.parse(r.getString(1)));}}catch(SQLException failure){throw unavailable();}}
 public void retryAfter(NewsScope scope,Instant until){try(var c=connections.open();var p=c.prepareStatement("INSERT INTO seller_news_refresh(shop_id,marketplace,owner,retry_at) VALUES(?,?,?,?) ON CONFLICT(shop_id,marketplace,owner) DO UPDATE SET retry_at=excluded.retry_at")){bind(p,scope);p.setString(4,until.toString());p.executeUpdate();}catch(SQLException failure){throw unavailable();}}
 private static void bind(PreparedStatement p,NewsScope s)throws SQLException{p.setInt(1,s.shopId());p.setString(2,s.marketplace().name());p.setString(3,s.ownerFingerprint());}
 private static IllegalStateException unavailable(){return new IllegalStateException("Scoped seller news store unavailable");}
}
