package com.vncode.app.features.listingcopy;

import java.net.URI;
import java.nio.file.*;
import java.util.Set;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ListingMediaReaderTest {
    @TempDir Path root;
    @Test void redirectNeverForwardsCredentialAndTemporaryFilesAreDeleted() throws Exception {
        try(var a=new MockWebServer();var b=new MockWebServer()){
            a.start();b.start();a.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",b.url("/photo")));
            byte[] bytes=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6H3sAAAAASUVORK5CYII=");
            b.enqueue(new MockResponse().setHeader("Content-Type","image/png").setBody(new Buffer().write(bytes)));
            var http=new OkHttpClient.Builder().addInterceptor(c->c.proceed(c.request().newBuilder().header("Api-Key","FORBIDDEN").build())).build();
            var reader=new ListingMediaReader(http,root,u->u.getHost().equals("localhost"));
            Path file;try(var media=reader.fetch(URI.create(a.url("/a").toString()),"image/png")){file=media.temporaryFile();assertTrue(Files.exists(file));assertEquals(64,media.sha256().length());}
            assertFalse(Files.exists(file));assertNull(a.takeRequest().getHeader("Api-Key"));assertNull(b.takeRequest().getHeader("Api-Key"));
            try(var files=Files.list(root)){assertEquals(0,files.count());}
        }
    }
    @Test void oversizeOrMimeMismatchIsRejected() throws Exception {
        try(var server=new MockWebServer()){
            server.start();var reader=new ListingMediaReader(new OkHttpClient(),root,u->u.getHost().equals("localhost"));
            server.enqueue(new MockResponse().setHeader("Content-Type","image/png").setHeader("Content-Length",30*1024*1024));
            assertThrows(java.io.IOException.class,()->reader.fetch(URI.create(server.url("/large").toString()),"image/png"));
            server.enqueue(new MockResponse().setHeader("Content-Type","image/png").setBody("<html>wrong</html>"));
            assertThrows(java.io.IOException.class,()->reader.fetch(URI.create(server.url("/fake").toString()),"image/png"));
            try(var files=Files.list(root)){assertEquals(0,files.count());}
            assertThrows(java.io.IOException.class,()->new ListingMediaReader().fetch(URI.create("http://127.0.0.1/a"),"image/png"));
        }
    }
}
