package com.vncode.app.shared;

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
    static final class TestClock extends Clock {
        Instant now=Instant.parse("2026-10-11T00:00:00Z");public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return now;}
    }
    ThumbnailLoader loader(ShopReadCoordinator reads,TestClock clock) {
        return new ThumbnailLoader(new OkHttpClient.Builder().followRedirects(false).build(),uri->uri.getHost().equals("localhost"),reads,new ThumbnailCache<Image>(64L*1024*1024,image->(long)image.getWidth()*(long)image.getHeight()*4),clock);
    }
    @Test void oversizedImageRejectedBeforeDecode() throws Exception {
        try(var server=new MockWebServer();var reads=new ShopReadCoordinator(2,64);var loader=loader(reads,new TestClock())) {
            server.enqueue(new MockResponse().setHeader("Content-Length",9*1024*1024));
            assertThrows(ExecutionException.class,()->loader.load(server.url("/large").uri(),96,128).get(5,TimeUnit.SECONDS));
            assertEquals(1,server.getRequestCount());
        }
    }
    @Test void failureExpiresAndCanRecover() throws Exception {
        var clock=new TestClock();
        try(var server=new MockWebServer();var reads=new ShopReadCoordinator(2,64);var loader=loader(reads,clock)) {
            server.enqueue(new MockResponse().setResponseCode(503));var uri=server.url("/image").uri();
            assertThrows(ExecutionException.class,()->loader.load(uri,96,128).get(5,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,()->loader.load(uri,96,128).get(5,TimeUnit.SECONDS));assertEquals(1,server.getRequestCount());
            clock.now=clock.now.plusSeconds(301);var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(400,600,BufferedImage.TYPE_INT_RGB),"png",out);
            server.enqueue(new MockResponse().setHeader("Content-Type","image/png").setBody(new okio.Buffer().write(out.toByteArray())));
            var image=loader.load(uri,96,128).get(5,TimeUnit.SECONDS);assertFalse(image.isError());assertTrue(image.getWidth()<=96);assertTrue(image.getHeight()<=128);assertEquals(2,server.getRequestCount());
        }
    }
    @Test void deniedHostNeverGetsARequestAndErrorsAreSanitized() throws Exception {
        try(var server=new MockWebServer();var reads=new ShopReadCoordinator(2,64);var loader=loader(reads,new TestClock())) {
            var error=assertThrows(ExecutionException.class,()->loader.load(java.net.URI.create("https://invalid.example/private?token=secret"),96,128).get(5,TimeUnit.SECONDS));
            assertFalse(error.getMessage().contains("secret"));assertEquals(0,server.getRequestCount());
        }
    }
}
