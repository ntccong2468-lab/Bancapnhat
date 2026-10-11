package com.vncode.app.features.news;
import com.vncode.app.config.Database;
import com.vncode.app.integration.translation.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
public final class NewsTranslationService implements AutoCloseable {
 private volatile boolean closed;
 public void close(){closed=true;if(translator!=null)translator.close();}
 public enum State {TRANSLATED,UNCONFIGURED,FAILED}
 public record TranslatedNews(SellerNews original,String translatedTitle,String translatedBody,String providerVersion,String hash,State state){}
 @FunctionalInterface private interface Connections{Connection open()throws SQLException;}
 private final Connections connections;private final NewsTranslator translator;
 public NewsTranslationService(NewsTranslator translator){connections=Database::getConnection;this.translator=translator;init();}
 public NewsTranslationService(Path file,NewsTranslator translator){connections=()->DriverManager.getConnection("jdbc:sqlite:"+file);this.translator=translator;init();}
 public static void ensureTables(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS news_translations(owner TEXT NOT NULL,hash TEXT NOT NULL,language TEXT NOT NULL,provider TEXT NOT NULL,title TEXT,body TEXT,state TEXT NOT NULL,checked_at TEXT NOT NULL,PRIMARY KEY(owner,hash,language,provider))");}}
 private void init(){try(var c=connections.open()){ensureTables(c);}catch(SQLException failure){throw new IllegalStateException("Translation cache unavailable");}}
 public synchronized TranslatedNews translate(SellerNews news,String target){
  String version=translator==null?"unconfigured":translator.version(),hash=NewsScope.hash(news.revision()+"|"+news.originalTitle()+"|"+news.originalBody());
  if(translator==null||closed)return result(news,"","",version,hash,State.UNCONFIGURED);
  if(!"vi".equals(target))throw new IllegalArgumentException("Seller news translation target is Vietnamese");
  String owner=news.scope().shopId()+":"+news.scope().marketplace()+":"+news.scope().ownerFingerprint();
  try(var c=connections.open();var p=c.prepareStatement("SELECT * FROM news_translations WHERE owner=? AND hash=? AND language=? AND provider=?")){p.setString(1,owner);p.setString(2,hash);p.setString(3,target);p.setString(4,version);try(var r=p.executeQuery()){if(r.next()){State state=State.valueOf(r.getString("state"));if(state==State.TRANSLATED||Instant.parse(r.getString("checked_at")).plusSeconds(1800).isAfter(Instant.now()))return result(news,r.getString("title"),r.getString("body"),version,hash,state);}}}catch(SQLException|RuntimeException failure){return result(news,"","",version,hash,State.FAILED);}
  State state=State.FAILED;String title="",body="";
  try{var texts=translator.translate(List.of(news.originalTitle(),news.originalBody()),"ru",target);if(texts.size()!=2||texts.get(0).isBlank()||!literals(news.originalTitle()).equals(literals(texts.get(0)))||!literals(news.originalBody()).equals(literals(texts.get(1))))throw new IllegalArgumentException();title=texts.get(0);body=texts.get(1);state=State.TRANSLATED;}catch(Exception unavailable){}
  try(var c=connections.open();var p=c.prepareStatement("INSERT INTO news_translations VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(owner,hash,language,provider) DO UPDATE SET title=excluded.title,body=excluded.body,state=excluded.state,checked_at=excluded.checked_at")){Object[] values={owner,hash,target,version,title,body,state.name(),Instant.now().toString()};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}catch(SQLException failure){return result(news,"","",version,hash,State.FAILED);}
  return result(news,title,body,version,hash,state);
 }
 private static List<String> literals(String text){var out=new ArrayList<String>();var match=Pattern.compile("[0-9]+(?:[.,/:\\-][0-9]+)*%?").matcher(text);while(match.find())out.add(match.group());Collections.sort(out);return List.copyOf(out);}
 private static TranslatedNews result(SellerNews n,String t,String b,String v,String h,State s){return new TranslatedNews(n,Objects.requireNonNullElse(t,""),Objects.requireNonNullElse(b,""),v,h,s);}
}
