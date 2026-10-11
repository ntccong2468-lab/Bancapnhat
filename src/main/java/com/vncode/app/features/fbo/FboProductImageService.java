package com.vncode.app.features.fbo;

import com.vncode.app.features.print.history.ImageCacheRepository;
import com.vncode.app.features.print.history.PrintHistoryService;
import com.vncode.app.shared.MarketplaceMedia;
import com.vncode.app.shared.ShopReadCoordinator;
import com.vncode.app.shared.ThumbnailCache;
import okhttp3.OkHttpClient;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Bounded screen thumbnails; loadImage retains full resolution for printing/history. */
public class FboProductImageService {
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(12)).callTimeout(Duration.ofSeconds(20)).build();
    private static final ShopReadCoordinator IMAGES=ShopReadCoordinator.images();
    private static final ThumbnailCache<byte[]> MEMORY=new ThumbnailCache<>(32L*1024*1024,bytes->bytes.length);
    private static final LinkedHashMap<String,Instant> FAILED=new LinkedHashMap<>();
    private final ImageCacheRepository repository=new ImageCacheRepository();
    private final OkHttpClient client;private final Predicate<URI> policy;private final Clock clock;
    public FboProductImageService(){this(HTTP,MarketplaceMedia::allowed,Clock.systemUTC());}
    public FboProductImageService(OkHttpClient client,Predicate<URI> policy,Clock clock){this.client=MarketplaceMedia.credentialFree(client);this.policy=Objects.requireNonNull(policy);this.clock=Objects.requireNonNull(clock);}
    public static long memoryBudget(){return MEMORY.budgetBytes();}
    public CompletableFuture<byte[]> loadImage(String url){return load(url,false);}
    public CompletableFuture<byte[]> loadThumbnailBytes(String url){return load(url,true);}
    private CompletableFuture<byte[]> load(String url,boolean thumbnail) {
        URI uri;try{uri=URI.create(url);}catch(RuntimeException bad){return CompletableFuture.completedFuture(null);}
        String fullKey=PrintHistoryService.imageCacheKey(url);if(fullKey==null)return CompletableFuture.completedFuture(null);
        String key=fullKey+(thumbnail?":thumbnail96x128":"");var existing=MEMORY.get(key);if(existing!=null)return CompletableFuture.completedFuture(existing);
        synchronized(FAILED){var until=FAILED.get(key);if(until!=null){if(clock.instant().isBefore(until))return CompletableFuture.completedFuture(null);FAILED.remove(key);}}
        return IMAGES.submit(new ShopReadCoordinator.Key(1,"product-image",key),true,()->{
            try {
                var source=repository.findImage(fullKey);boolean downloaded=source==null||source.length==0;
                if(downloaded)source=MarketplaceMedia.download(client,uri,policy);
                byte[] result=MarketplaceMedia.png(source,thumbnail?96:0,thumbnail?128:0);
                MEMORY.put(key,result);if(downloaded&&!thumbnail)repository.saveImage(fullKey,url,result,"image/png");return result;
            }catch(Exception invalid){synchronized(FAILED){while(FAILED.size()>=256)FAILED.remove(FAILED.keySet().iterator().next());FAILED.put(key,clock.instant().plusSeconds(300));}return null;}
        }).exceptionally(error->null);
    }
}
