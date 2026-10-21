package com.vncode.app.shared;

import static com.vncode.app.testsupport.MockServerOrigins.allow;

import javafx.scene.image.Image;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ThumbnailLoaderTest {
    @Test void cachedBytesDecodeOnImageWorkersAtThumbnailSize()throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var reads=new ShopReadCoordinator(2,64);var loader=loader(reads,new TestClock())){
            var blockers=new java.util.ArrayList<CompletableFuture<Boolean>>();for(int i=0;i<2;i++){int id=i+1;blockers.add(reads.submit(new ShopReadCoordinator.Key(id,"test-block","1"),true,()->{entered.countDown();release.await(5,TimeUnit.SECONDS);return true;}));}assertTrue(entered.await(3,TimeUnit.SECONDS));
            var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1200,1600,BufferedImage.TYPE_INT_RGB),"png",out);var future=loader.loadBytes(out.toByteArray(),96,128);
            try{assertFalse(future.isDone(),"Decode must wait for image workers, never run on the caller thread");}finally{release.countDown();for(var blocker:blockers)blocker.get(3,TimeUnit.SECONDS);}
            var image=future.get(5,TimeUnit.SECONDS);assertTrue(image.getWidth()<=96);assertTrue(image.getHeight()<=128);assertFalse(image.isError());
        }finally{release.countDown();}
    }
    static final class TestClock extends Clock {
        Instant now=Instant.parse("2026-10-11T00:00:00Z");public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return now;}
    }
    ThumbnailLoader loader(ShopReadCoordinator reads,TestClock clock,MockWebServer... servers) {
        return new ThumbnailLoader(new OkHttpClient.Builder().followRedirects(false).build(),allow(servers),reads,new ThumbnailCache<Image>(64L*1024*1024,image->(long)image.getWidth()*(long)image.getHeight()*4),clock);
    }
    @Test void oversizedImageRejectedBeforeDecode() throws Exception {
        try(var server=new MockWebServer();var reads=new ShopReadCoordinator(2,64);var loader=loader(reads,new TestClock(),server)) {
            server.enqueue(new MockResponse().setHeader("Content-Length",9*1024*1024));
            assertThrows(ExecutionException.class,()->loader.load(server.url("/large").uri(),96,128).get(5,TimeUnit.SECONDS));
            assertEquals(1,server.getRequestCount());
        }
    }
    @Test void failureExpiresAndCanRecover() throws Exception {
        var clock=new TestClock();
        try(var server=new MockWebServer();var reads=new ShopReadCoordinator(2,64);var loader=loader(reads,clock,server)) {
            server.enqueue(new MockResponse().setResponseCode(503));var uri=server.url("/image").uri();
            assertThrows(ExecutionException.class,()->loader.load(uri,96,128).get(5,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,()->loader.load(uri,96,128).get(5,TimeUnit.SECONDS));assertEquals(1,server.getRequestCount());
            clock.now=clock.now.plusSeconds(301);var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(400,600,BufferedImage.TYPE_INT_RGB),"png",out);
            server.enqueue(new MockResponse().setHeader("Content-Type","image/png").setBody(new okio.Buffer().write(out.toByteArray())));
            var image=loader.load(uri,96,128).get(5,TimeUnit.SECONDS);assertFalse(image.isError());assertTrue(image.getWidth()<=96);assertTrue(image.getHeight()<=128);assertEquals(2,server.getRequestCount());
        }
    }
    @Test void deniedHostNeverGetsARequestAndErrorsAreSanitized() throws Exception {
        try(var server=new MockWebServer();var reads=new ShopReadCoordinator(2,64);var loader=loader(reads,new TestClock(),server)) {
            var error=assertThrows(ExecutionException.class,()->loader.load(java.net.URI.create("https://invalid.example/private?token=secret"),96,128).get(5,TimeUnit.SECONDS));
            assertFalse(error.getMessage().contains("secret"));assertEquals(0,server.getRequestCount());
        }
    }
}
