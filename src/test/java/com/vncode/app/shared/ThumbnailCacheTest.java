package com.vncode.app.shared;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThumbnailCacheTest {
    @Test void lruNeverExceeds64MiB() {
        var cache=new ThumbnailCache<byte[]>(64L*1024*1024,bytes->bytes.length);
        var bytes=new byte[32*1024*1024];cache.put("a",bytes);cache.put("b",bytes);assertNotNull(cache.get("a"));cache.put("c",bytes);
        assertNull(cache.get("b"));assertNotNull(cache.get("a"));assertNotNull(cache.get("c"));assertEquals(64L*1024*1024,cache.retainedBytes());
        cache.put("oversized",new byte[65*1024*1024]);assertNull(cache.get("oversized"));assertTrue(cache.retainedBytes()<=64L*1024*1024);
    }
    @Test void defaultImageCachesShareTwoDecodeWorkersAnd64MiBBudget() {
        assertSame(ShopReadCoordinator.images(),ShopReadCoordinator.images());
        assertEquals(2,ShopReadCoordinator.images().workerLimit());
        assertEquals(64L*1024*1024,ThumbnailLoader.memoryBudget()+com.vncode.app.features.fbo.FboProductImageService.memoryBudget());
    }
    @Test void replacementAndClearReleaseWeights() {
        var cache=new ThumbnailCache<String>(10,String::length);cache.put("a","12345");cache.put("a","1");assertEquals(1,cache.retainedBytes());cache.clear();assertEquals(0,cache.retainedBytes());
    }
}
