package com.vncode.app.shared;

import okhttp3.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Credential-free media transport and image validation shared by screen thumbnails/full print images. */
public final class MarketplaceMedia {
    public static final long MAX_BYTES=8L*1024*1024,MAX_PIXELS=40_000_000L;
    private MarketplaceMedia(){}
    public static boolean allowed(URI uri) {
        if(uri==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getHost()==null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        return List.of("wbbasket.ru","wbstatic.net","ozone.ru","ozon.ru").stream().anyMatch(h->host.equals(h)||host.endsWith("."+h));
    }
    public static OkHttpClient credentialFree(OkHttpClient client) {
        var builder=client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE);
        builder.interceptors().clear();builder.networkInterceptors().clear();return builder.build();
    }
    public static byte[] download(OkHttpClient client,URI source,Predicate<URI> policy)throws IOException {
        URI current=source;
        for(int hop=0;hop<4;hop++) {
            if(current==null||current.getHost()==null||current.getUserInfo()!=null||!policy.test(current))throw new IOException("Media source is not permitted");
            try(var response=client.newCall(new Request.Builder().url(current.toASCIIString()).get().build()).execute()) {
                if(response.code()>=300&&response.code()<400){String location=response.header("Location");if(location==null)throw new IOException("Invalid media redirect");current=current.resolve(location);continue;}
                if(!response.isSuccessful()||response.body()==null)throw new IOException("Media unavailable");
                if(response.body().contentLength()>MAX_BYTES)throw new IOException("Media exceeds size limit");
                var body=response.body().byteStream().readNBytes((int)MAX_BYTES+1);if(body.length>MAX_BYTES)throw new IOException("Media exceeds size limit");return body;
            }catch(IllegalArgumentException malformed){throw new IOException("Invalid media response");}
        }
        throw new IOException("Too many media redirects");
    }
    public static byte[] png(byte[] encoded,int targetWidth,int targetHeight)throws IOException {
        if(encoded==null||encoded.length==0||encoded.length>MAX_BYTES)throw new IOException("Invalid image size");
        try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("Unsupported image");var reader=readers.next();
            try {reader.setInput(stream,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<=0||height<=0||(long)width*height>MAX_PIXELS)throw new IOException("Image resolution exceeds limit");
                var params=reader.getDefaultReadParam();if(targetWidth>0&&targetHeight>0){int sample=Math.max(1,(int)Math.ceil(Math.max((double)width/targetWidth,(double)height/targetHeight)));params.setSourceSubsampling(sample,sample,0,0);}
                var image=reader.read(0,params);if(image==null)throw new IOException("Invalid image");var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);image.flush();return out.toByteArray();
            }finally{reader.dispose();}
        }catch(RuntimeException invalid){throw new IOException("Invalid image");}
    }
    public static String cacheKey(URI uri,int width,int height) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((uri.toASCIIString()+"|"+width+"|"+height).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
