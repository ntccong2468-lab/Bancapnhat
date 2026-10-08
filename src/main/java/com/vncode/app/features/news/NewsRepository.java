package com.vncode.app.features.news;
import com.google.gson.Gson;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
public final class NewsRepository {
 private final Path directory;
 private final Set<String> read=new HashSet<>();
 public NewsRepository(Path root) {
  directory=root.resolve("news");
  try { var value=new Gson().fromJson(Files.readString(directory.resolve("read.json")),String[].class); if(value!=null)read.addAll(Arrays.asList(value)); } catch(IOException|RuntimeException ignored) {}
 }
 public synchronized boolean isRead(String id) { return read.contains(id); }
 public synchronized void markRead(String id) throws IOException {
  if(id==null||id.isBlank())throw new IllegalArgumentException("News ID is required");
  var updated=new HashSet<>(read); updated.add(id);
  write("read.json",new Gson().toJson(updated));read.add(id);
 }
 public synchronized void cache(String json) throws IOException { write("cache.json",json); }
 public synchronized String cached() throws IOException { return Files.readString(directory.resolve("cache.json")); }
 private void write(String name,String content) throws IOException {
  Files.createDirectories(directory);Path tmp=Files.createTempFile(directory,"news-",".tmp");
  try { Files.writeString(tmp,content);try { Files.move(tmp,directory.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); } catch(AtomicMoveNotSupportedException e) { Files.move(tmp,directory.resolve(name),StandardCopyOption.REPLACE_EXISTING); } } finally {Files.deleteIfExists(tmp);}
 }
}
