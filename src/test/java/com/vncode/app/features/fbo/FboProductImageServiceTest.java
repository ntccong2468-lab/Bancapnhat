package com.vncode.app.features.fbo;

import com.vncode.app.config.Database;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FboProductImageServiceTest {
    @TempDir Path root;
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void validatedHistoricalCacheDoesNotRequireRemoteHostPermission() throws Exception {
        var body=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(60,90,BufferedImage.TYPE_INT_RGB),"png",body);
        var url="https://historical.invalid/"+root.getFileName()+".png";
        new com.vncode.app.features.print.history.ImageCacheRepository().saveImage(url,url,body.toByteArray(),"image/png");
        assertNotNull(new FboProductImageService().loadImage(url).get(5,TimeUnit.SECONDS));
    }
    @Test void thumbnailsStaySmallButPrintImagesKeepFullResolution() throws Exception {
        try(var server=new MockWebServer()) {
            var body=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(600,900,BufferedImage.TYPE_INT_RGB),"png",body);
            for(int i=0;i<2;i++)server.enqueue(new MockResponse().setBody(new okio.Buffer().write(body.toByteArray())).setHeader("Content-Type","image/png"));
            var service=new FboProductImageService(new OkHttpClient(),uri->uri.getHost().equals("localhost"),Clock.systemUTC());var url=server.url("/photo").toString();
            var thumb=ImageIO.read(new ByteArrayInputStream(service.loadThumbnailBytes(url).get(5,TimeUnit.SECONDS)));
            var full=ImageIO.read(new ByteArrayInputStream(service.loadImage(url).get(5,TimeUnit.SECONDS)));
            assertTrue(thumb.getWidth()<=96);assertTrue(thumb.getHeight()<=128);assertEquals(600,full.getWidth());assertEquals(900,full.getHeight());
        }
    }
}
