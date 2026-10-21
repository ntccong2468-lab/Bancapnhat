package com.vncode.app.integration.translation;
import com.vncode.app.config.Database;
import com.vncode.app.shared.WindowsSecretProtector;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
public final class TranslationSettingsRepository {
 public record Settings(URI endpoint,String folderId,String apiKey,String revision){@Override public String toString(){return "TranslationSettings[credentials redacted]";}}
 @FunctionalInterface private interface Connections{Connection open()throws SQLException;}
 private final Connections connections;private final WindowsSecretProtector protector=new WindowsSecretProtector();
 public TranslationSettingsRepository(){connections=Database::getConnection;init();}
 public TranslationSettingsRepository(Path path){connections=()->DriverManager.getConnection("jdbc:sqlite:"+path);init();}
 public static void ensureTables(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS news_translation_settings(id INTEGER PRIMARY KEY CHECK(id=1),endpoint TEXT NOT NULL,folder_id TEXT NOT NULL,protected_key TEXT NOT NULL,revision TEXT NOT NULL)");}}
 private void init(){try(var c=connections.open()){ensureTables(c);}catch(SQLException failure){throw new IllegalStateException("Translation settings unavailable");}}
 public void save(URI endpoint,String folderId,String key)throws IOException {
  if(!YandexNewsTranslator.ENDPOINT.equals(endpoint)||folderId==null||!folderId.matches("[A-Za-z0-9_-]{0,50}")||key==null||key.isBlank()||key.length()>8192||key.chars().anyMatch(c->c<33||c>126))throw new IOException("Invalid translation settings");
  if(!WindowsSecretProtector.supported())throw new IOException("Windows DPAPI is required for the translation key");
  String encrypted;try{encrypted=protector.protect(key);}catch(IOException error){throw new IOException("Translation key protection unavailable");}
  try(var c=connections.open();var p=c.prepareStatement("INSERT INTO news_translation_settings VALUES(1,?,?,?,?) ON CONFLICT(id) DO UPDATE SET endpoint=excluded.endpoint,folder_id=excluded.folder_id,protected_key=excluded.protected_key,revision=excluded.revision")){p.setString(1,endpoint.toString());p.setString(2,folderId);p.setString(3,encrypted);p.setString(4,UUID.randomUUID().toString());p.executeUpdate();}catch(SQLException failure){throw new IOException("Translation settings unavailable");}
 }
 public Optional<Settings> load()throws IOException {
  try(var c=connections.open();var p=c.prepareStatement("SELECT * FROM news_translation_settings WHERE id=1");var r=p.executeQuery()){
   if(!r.next())return Optional.empty();URI endpoint=URI.create(r.getString("endpoint"));if(!YandexNewsTranslator.ENDPOINT.equals(endpoint))throw new IOException("Untrusted translation endpoint");String raw;try{raw=protector.reveal(r.getString("protected_key"));}catch(IOException failure){throw new IOException("Translation key cannot be read by this Windows user");}return Optional.of(new Settings(endpoint,r.getString("folder_id"),raw,r.getString("revision")));
  }catch(SQLException|IllegalArgumentException failure){throw new IOException("Translation settings unavailable");}
 }
 public void clear(){try(var c=connections.open();var s=c.createStatement()){s.executeUpdate("DELETE FROM news_translation_settings");}catch(SQLException failure){throw new IllegalStateException("Translation settings unavailable");}}
}
