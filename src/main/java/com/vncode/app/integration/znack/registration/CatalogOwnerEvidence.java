package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;

/** Fresh positive owner evidence from the official INN-filtered Catalog version index. */
final class CatalogOwnerEvidence {
    @FunctionalInterface interface Reader { JsonElement read(String ownerInn,int offset) throws Exception; }
    private static final int MAX_ITEMS=100_000, MAX_PAGE=100;
    private static final Duration DEADLINE=Duration.ofMinutes(3);

    /**
     * A match proves only that this exact good_id appeared in the authenticated
     * participant's fresh index. Missing/partial pages are never a negative ownership
     * result. The caller must also read back the exact GTIN/card/status and guard the
     * current shop, certificate and API-key scope before using this evidence.
     */
    static boolean verify(String ownerInn,long goodId,String expectedEtag,Reader reader,Runnable scopeGuard)throws Exception {
        if(ownerInn==null||!ownerInn.matches("[0-9]{10}|[0-9]{12}")||goodId<=0)throw unknown();
        Objects.requireNonNull(reader,"reader");Objects.requireNonNull(scopeGuard,"scopeGuard");
        long deadline=System.nanoTime()+DEADLINE.toNanos();int offset=0;Integer total=null;
        var seen=new HashSet<String>();String wanted=Long.toString(goodId);
        for(int page=0;page<=(MAX_ITEMS/MAX_PAGE);page++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("CATALOG_OWNER_LOOKUP_INTERRUPTED");
            if(System.nanoTime()>deadline)throw new java.net.SocketTimeoutException("CATALOG_OWNER_LOOKUP_TIMEOUT");
            scopeGuard.run();JsonElement raw=reader.read(ownerInn,offset);scopeGuard.run();
            var result=object(raw);var goods=result.get("goods");
            if(goods==null||!goods.isJsonArray()||goods.getAsJsonArray().size()>MAX_PAGE)throw unknown();
            int size=goods.getAsJsonArray().size(),pageTotal=number(result,"total"),next=number(result,"last_product_number");
            if(pageTotal<0||pageTotal>MAX_ITEMS||total!=null&&pageTotal!=total||next!=offset+size||next>pageTotal||size==0&&next<pageTotal
                    ||number(result,"offset")!=offset||number(result,"goods_count")!=size)throw unknown();
            total=pageTotal;boolean matched=false;
            for(var element:goods.getAsJsonArray()){
                if(!element.isJsonObject())throw unknown();var entry=element.getAsJsonObject();
                String id=text(entry,"good_id"),etag=text(entry,"etag");
                if(entry.has("etag")&&!entry.get("etag").isJsonNull()&&(!entry.get("etag").isJsonPrimitive()||!entry.getAsJsonPrimitive("etag").isString()))throw unknown();
                if(!id.matches("[1-9][0-9]{0,18}")||etag.length()>512||!seen.add(id))throw unknown();
                if(id.equals(wanted)){
                    if(expectedEtag!=null&&!expectedEtag.isBlank()&&!expectedEtag.equals(etag))throw unknown();
                    matched=true;
                }
            }
            if(matched){scopeGuard.run();return true;}
            if(next==pageTotal)throw unknown();
            offset=next;
        }
        throw unknown();
    }

    private static JsonObject object(JsonElement raw){
        if(raw==null||!raw.isJsonObject())throw unknown();
        var root=raw.getAsJsonObject();
        if(root.has("error")&&!root.get("error").isJsonNull()||root.has("errors")&&!root.get("errors").isJsonNull()
                ||root.has("apiversion")&&number(root,"apiversion")!=3
                ||!root.has("result")||!root.get("result").isJsonObject())throw unknown();
        return root.getAsJsonObject("result");
    }
    private static int number(JsonObject raw,String key){
        try{var value=raw.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw unknown();return value.getAsBigDecimal().intValueExact();}
        catch(ArithmeticException invalid){throw unknown();}
    }
    private static String text(JsonObject raw,String key){
        try{var value=raw.get(key);return value==null||!value.isJsonPrimitive()||value.getAsJsonPrimitive().isBoolean()?"":value.getAsString().strip();}
        catch(RuntimeException invalid){throw unknown();}
    }
    private static IllegalStateException unknown(){return new IllegalStateException("CATALOG_OWNER_UNCONFIRMED");}
    private CatalogOwnerEvidence() { }
}
