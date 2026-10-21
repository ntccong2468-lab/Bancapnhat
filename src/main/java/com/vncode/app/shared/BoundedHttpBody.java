package com.vncode.app.shared;
import okhttp3.ResponseBody;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
public final class BoundedHttpBody {
    private BoundedHttpBody(){}
    public static byte[] bytes(ResponseBody body,int maximum)throws IOException {
        if(maximum<1||maximum==Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid body limit");
        if(body==null)return new byte[0];
        if(body.contentLength()>maximum)throw new IOException("Marketplace response too large");
        try(var stream=body.byteStream()){byte[] bytes=stream.readNBytes(maximum+1);if(bytes.length>maximum)throw new IOException("Marketplace response too large");return bytes;}
    }
    public static String utf8(ResponseBody body,int maximum)throws IOException{return new String(bytes(body,maximum),StandardCharsets.UTF_8);}
}
