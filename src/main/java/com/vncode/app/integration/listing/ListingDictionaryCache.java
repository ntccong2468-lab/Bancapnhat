package com.vncode.app.integration.listing;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dictionaries are reused within one operation; task/error pages only within an explicit readback poll. */
public final class ListingDictionaryCache implements ListingTransport {
    private final ListingTransport upstream;
    private final Map<String,JsonElement> entries=new HashMap<>();
    private long bytes;
    private boolean readback;
    public ListingDictionaryCache(ListingTransport upstream){this.upstream=Objects.requireNonNull(upstream);}
    public void beginReadback(){readback=true;}
    public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{return call(path,body,mutation,()->{});}
    public JsonElement call(String path,JsonElement body,boolean mutation,Runnable guard)throws IOException{
        boolean dictionary=!mutation&&(path.startsWith("content/v2/object/charcs/")||path.startsWith("v1/description-category/attribute")||path.equals("v2/product/certification/options")||path.equals("v2/product/certification/params")||readback&&(path.equals("v1/product/import/info")||path.equals("content/v2/cards/error/list")));
        if(!dictionary)return upstream.call(path,body,mutation,guard);
        String key=path+"\n"+(body==null?"":ListingIdentity.canonical(body));
        var cached=entries.get(key);if(cached!=null){guard.run();return cached.deepCopy();}
        var result=upstream.call(path,body,false,guard);
        if(result!=null&&result.isJsonObject()&&!"true".equals(ListingJson.text(result.getAsJsonObject(),"error"))){
            long size=result.toString().getBytes(StandardCharsets.UTF_8).length;
            if(entries.size()<1000&&bytes+size<=8L*1024*1024){entries.put(key,result.deepCopy());bytes+=size;}
        }
        return result;
    }
}
