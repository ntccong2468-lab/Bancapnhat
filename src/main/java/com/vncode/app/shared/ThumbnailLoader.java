package com.vncode.app.shared;

import javafx.scene.image.Image;
import okhttp3.OkHttpClient;
import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Small images decoded off the FX thread; cache and pending work are shared and bounded. */
public final class ThumbnailLoader implements AutoCloseable {
    private static final ShopReadCoordinator READS=ShopReadCoordinator.images();
    private static final ThumbnailCache<Image> CACHE=new ThumbnailCache<>(32L*1024*1024,i->(long)Math.ceil(i.getWidth())*(long)Math.ceil(i.getHeight())*4);
    private static final class ByteKey {final long id;volatile String digest;ByteKey(long id){this.id=id;}}
    private static final Map<byte[],ByteKey> BYTE_KEYS=new WeakHashMap<>();private static long byteSequence;
    private static final Object[] CONTENT_LOCKS=new Object[64];
    static {Arrays.setAll(CONTENT_LOCKS,i->new Object());}
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
        ByteKey identity;synchronized(BYTE_KEYS){var prior=BYTE_KEYS.get(bytes);if(prior==null){if(BYTE_KEYS.size()>=512)BYTE_KEYS.remove(BYTE_KEYS.keySet().iterator().next());prior=new ByteKey(++byteSequence);BYTE_KEYS.put(bytes,prior);}identity=prior;}
        String dimensions=":"+width+"x"+height;String known=identity.digest;
        if(known!=null){var existing=cache.get("content-"+known+dimensions);if(existing!=null)return CompletableFuture.completedFuture(existing);}
        String workKey="bytes-"+identity.id+dimensions;
        return reads.submit(new ShopReadCoordinator.Key(1,"thumbnail-bytes",workKey),true,()->{
            // Database reads can return a new byte[] for the same picture each time.
            // Hash off the FX thread, then reuse the decoded, size-specific image.
            String digest=identity.digest;
            if(digest==null)synchronized(identity){digest=identity.digest;if(digest==null)identity.digest=digest=contentDigest(bytes);}
            String key="content-"+digest+dimensions;Object lock=CONTENT_LOCKS[key.hashCode()&(CONTENT_LOCKS.length-1)];
            synchronized(lock){var existing=cache.get(key);if(existing!=null)return existing;
                var png=MarketplaceMedia.png(bytes,width,height);var image=new Image(new ByteArrayInputStream(png));if(image.isError())throw new IOException("Thumbnail unavailable");cache.put(key,image);return image;}
        });
    }
    private static String contentDigest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    public static long sharedRetainedBytes(){return CACHE.retainedBytes();}
    public long retainedBytes(){return cache.retainedBytes();}
    public void close(){closed=true;synchronized(failures){failures.clear();}}
}
