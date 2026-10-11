package com.vncode.app.features.listingcopy;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import okhttp3.*;

/** Full-size media only. Never accepts seller credentials and cleans partial downloads. */
public final class ListingMediaReader {
    public static final int MAX_FILES=20,IMAGE_LIMIT=20*1024*1024,DOCUMENT_LIMIT=25*1024*1024;
    private static final OkHttpClient SHARED_HTTP=new OkHttpClient.Builder().dns(host->{var addresses=Dns.SYSTEM.lookup(host);for(var a:addresses)if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())throw new java.net.UnknownHostException("Private media address rejected");return addresses;}).build();
    private final OkHttpClient http;private final Path directory;private final Predicate<URI> allowed;
    public record MediaFile(Path temporaryFile,String sha256,long size,String mimeType) implements AutoCloseable {public void close()throws IOException{Files.deleteIfExists(temporaryFile);}}
    public ListingMediaReader(){this(SHARED_HTTP,Path.of(System.getProperty("java.io.tmpdir")),ListingMediaReader::allowedMarketplaceUrl);}
    public ListingMediaReader(OkHttpClient client,Path directory,Predicate<URI> allowed){
        var b=client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS);b.interceptors().clear();b.networkInterceptors().clear();http=b.build();this.directory=directory;this.allowed=allowed;
    }
    public MediaFile fetch(URI uri,String expectedMime)throws IOException{
        for(int hop=0;hop<=4;hop++){
            if(uri==null||uri.getRawUserInfo()!=null||uri.getFragment()!=null||!allowed.test(uri))throw new IOException("Unsupported media source");
            var request=new Request.Builder().url(uri.toString()).get().build();
            try(var r=http.newCall(request).execute()){
                if(r.isRedirect()){String next=r.header("Location");if(next==null||hop==4)throw new IOException("Media redirect limit");uri=uri.resolve(next);continue;}
                if(!r.isSuccessful()||r.body()==null)throw new IOException("Media download unavailable");
                int limit=expectedMime!=null&&expectedMime.equals("application/pdf")?DOCUMENT_LIMIT:IMAGE_LIMIT;
                if(r.body().contentLength()>limit)throw new IOException("Media exceeds file limit");
                Files.createDirectories(directory);Path file=Files.createTempFile(directory,"vncode-listing-",".media");boolean retained=false;
                try(var in=r.body().byteStream();var out=Files.newOutputStream(file)){
                    var digest=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[8192];long size=0;int n;
                    while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Media download cancelled");size+=n;if(size>limit)throw new IOException("Media exceeds file limit");out.write(buf,0,n);digest.update(buf,0,n);}
                    out.flush();String mime=magic(file);String declared=Objects.toString(r.header("Content-Type"),"").split(";")[0];
                    if(mime==null||(expectedMime!=null&&!expectedMime.isBlank()&&!expectedMime.equals(mime))||(!declared.isBlank()&&!declared.equals("application/octet-stream")&&!declared.equals(mime)))throw new IOException("Media type mismatch");
                    retained=true;return new MediaFile(file,HexFormat.of().formatHex(digest.digest()),size,mime);
                }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}finally{if(!retained)Files.deleteIfExists(file);}
            }
        }throw new IOException("Media unavailable");
    }
    public static boolean allowedMarketplaceUrl(URI u){
        if(u==null||!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||(u.getPort()!=-1&&u.getPort()!=443))return false;
        String h=u.getHost().toLowerCase(Locale.ROOT);return h.endsWith(".wbbasket.ru")||h.endsWith(".wbstatic.net")||h.endsWith(".ozone.ru")||h.endsWith(".ozon.ru");
    }
    public static String magic(Path file)throws IOException{byte[] b;try(var in=Files.newInputStream(file)){b=in.readNBytes(16);}if(b.length>=8&&b[0]==(byte)0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G')return "image/png";if(b.length>=3&&b[0]==(byte)0xff&&b[1]==(byte)0xd8&&b[2]==(byte)0xff)return "image/jpeg";if(b.length>=5&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'&&b[4]=='-')return "application/pdf";if(b.length>=12&&b[0]=='R'&&b[1]=='I'&&b[8]=='W'&&b[9]=='E')return "image/webp";return null;}
}
