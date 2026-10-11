package com.vncode.app.shared;

import javafx.scene.image.Image;
import okhttp3.OkHttpClient;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Small images decoded off the FX thread; cache and pending work are shared and bounded. */
public final class ThumbnailLoader implements AutoCloseable {
    private static final ShopReadCoordinator READS=ShopReadCoordinator.images();
    private static final ThumbnailCache<Image> CACHE=new ThumbnailCache<>(32L*1024*1024,i->(long)Math.ceil(i.getWidth())*(long)Math.ceil(i.getHeight())*4);
    private static final Map<byte[],Long> BYTE_KEYS=new WeakHashMap<>();private static long byteSequence;
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(12)).callTimeout(Duration.ofSeconds(20)).build();
    private final OkHttpClient client;private final Predicate<URI> policy;private final ShopReadCoordinator reads;private final ThumbnailCache<Image> cache;private final Clock clock;
    private final LinkedHashMap<String,Instant> failures=new LinkedHashMap<>();private volatile boolean closed;
    public ThumbnailLoader(){this(HTTP,MarketplaceMedia::allowed,READS,CACHE,Clock.systemUTC());}
    public ThumbnailLoader(OkHttpClient client,Predicate<URI> policy,ShopReadCoordinator reads,ThumbnailCache<Image> cache,Clock clock) {
        this.client=MarketplaceMedia.credentialFree(client);this.policy=Objects.requireNonNull(policy);this.reads=Objects.requireNonNull(reads);this.cache=Objects.requireNonNull(cache);this.clock=Objects.requireNonNull(clock);
    }
    public CompletableFuture<Image> load(URI uri,int width,int height) {
        if(closed||uri==null||width<1||height<1||width>2048||height>2048||!policy.test(uri))return CompletableFuture.failedFuture(new IOException("Thumbnail source unavailable"));
        String key=MarketplaceMedia.cacheKey(uri,width,height);var existing=cache.get(key);if(existing!=null)return CompletableFuture.completedFuture(existing);
        synchronized(failures){var retry=failures.get(key);if(retry!=null){if(clock.instant().isBefore(retry))return CompletableFuture.failedFuture(new IOException("Thumbnail temporarily unavailable"));failures.remove(key);}}
        return reads.submit(new ShopReadCoordinator.Key(1,"thumbnail",key),true,()->{
            try {
                byte[] png=MarketplaceMedia.png(MarketplaceMedia.download(client,uri,policy),width,height);
                var image=new Image(new ByteArrayInputStream(png));if(image.isError())throw new IOException("Unable to decode thumbnail");cache.put(key,image);return image;
            }catch(IOException failure){synchronized(failures){while(failures.size()>=256)failures.remove(failures.keySet().iterator().next());failures.put(key,clock.instant().plusSeconds(300));}throw new IOException("Thumbnail unavailable");}
        });
    }
    public static long memoryBudget(){return CACHE.budgetBytes();}
    public CompletableFuture<Image> loadBytes(byte[] bytes,int width,int height){
        if(closed||bytes==null||bytes.length==0||bytes.length>MarketplaceMedia.MAX_BYTES||width<1||height<1||width>2048||height>2048)return CompletableFuture.failedFuture(new IOException("Thumbnail source unavailable"));
        long id;synchronized(BYTE_KEYS){var prior=BYTE_KEYS.get(bytes);if(prior==null){if(BYTE_KEYS.size()>=512)BYTE_KEYS.remove(BYTE_KEYS.keySet().iterator().next());prior=++byteSequence;BYTE_KEYS.put(bytes,prior);}id=prior;}
        String key="bytes-"+id+":"+width+"x"+height;var existing=cache.get(key);if(existing!=null)return CompletableFuture.completedFuture(existing);
        return reads.submit(new ShopReadCoordinator.Key(1,"thumbnail-bytes",key),true,()->{var png=MarketplaceMedia.png(bytes,width,height);var image=new Image(new ByteArrayInputStream(png));if(image.isError())throw new IOException("Thumbnail unavailable");cache.put(key,image);return image;});
    }
    public static long sharedRetainedBytes(){return CACHE.retainedBytes();}
    public long retainedBytes(){return cache.retainedBytes();}
    public void close(){closed=true;synchronized(failures){failures.clear();}}
}
