package com.vncode.app.integration.listing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded run-local raw catalog cache. Eviction spills selected cards to private temp
 * files, so a large description cannot lose a card behind WB's incremental cursor.
 * No cached entry proves application of a write; only fresh GET pages replace entries. */
final class GtinSnapshotStore implements AutoCloseable {
    private record Entry(JsonObject value,long bytes){}
    private final long memoryLimit;
    private final LinkedHashMap<String,Entry> memory=new LinkedHashMap<>(256,.75f,true);
    private final Map<String,Path> backing=new HashMap<>();
    private Path directory;
    private long memoryBytes;
    GtinSnapshotStore(long memoryLimit){if(memoryLimit<1)throw new IllegalArgumentException("invalid_cache_limit");this.memoryLimit=memoryLimit;}
    long retainedBytes(){return memoryBytes;}
    JsonObject get(String key){
        var cached=memory.get(key);if(cached!=null)return cached.value();var file=backing.get(key);if(file==null)return null;
        try{
            if(Files.size(file)>20L*1024*1024)throw new IOException("GTIN_BATCH_CACHE_LIMIT");
            var value=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            cache(key,new Entry(value,bytes(value)));return value;
        }catch(IOException failure){throw new UncheckedIOException("GTIN_BATCH_CACHE_UNAVAILABLE",failure);}
    }
    void put(String key,JsonObject value){
        remove(key);var entry=new Entry(value.deepCopy(),bytes(value));
        try{cache(key,entry);if(!memory.containsKey(key))spill(key,entry);}
        catch(IOException failure){throw new UncheckedIOException("GTIN_BATCH_CACHE_UNAVAILABLE",failure);}
    }
    private void cache(String key,Entry entry)throws IOException {
        if(entry.bytes()>memoryLimit)return;
        while(memoryBytes+entry.bytes()>memoryLimit&&!memory.isEmpty()){
            var first=memory.entrySet().iterator().next();spill(first.getKey(),first.getValue());memoryBytes-=first.getValue().bytes();memory.remove(first.getKey());
        }
        memory.put(key,entry);memoryBytes+=entry.bytes();
    }
    private void spill(String key,Entry entry)throws IOException {
        if(backing.containsKey(key))return;
        if(directory==null)directory=Files.createTempDirectory("vncode-gtin-snapshot-");
        var file=Files.createTempFile(directory,"card-",".json");
        try{Files.writeString(file,entry.value().toString(),StandardCharsets.UTF_8);backing.put(key,file);}
        catch(IOException failure){Files.deleteIfExists(file);throw failure;}
    }
    void remove(String key){
        var entry=memory.remove(key);if(entry!=null)memoryBytes-=entry.bytes();var file=backing.remove(key);
        if(file!=null)try{Files.deleteIfExists(file);}catch(IOException failure){throw new UncheckedIOException("GTIN_BATCH_CACHE_UNAVAILABLE",failure);}
    }
    void clear(){
        memory.clear();memoryBytes=0;
        try{for(var file:backing.values())Files.deleteIfExists(file);backing.clear();if(directory!=null){Files.deleteIfExists(directory);directory=null;}}
        catch(IOException failure){throw new UncheckedIOException("GTIN_BATCH_CACHE_UNAVAILABLE",failure);}
    }
    private static long bytes(JsonObject value){return value.toString().getBytes(StandardCharsets.UTF_8).length;}
    @Override public void close(){clear();}
}
