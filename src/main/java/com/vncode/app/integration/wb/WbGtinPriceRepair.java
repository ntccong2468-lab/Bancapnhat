package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.OkHttpClient;

/** Before a selected GTIN write, equalize unequal WB size prices and verify the remote result.
 * A sent or possibly sent price request is never automatically repeated. */
public final class WbGtinPriceRepair {
    public enum State { READY, PENDING, RATE_LIMIT, FAILED }
    public record Result(State state, Duration retryAfter, String code) { }
    private record Snapshot(String currency, boolean editable, SortedMap<Long,Integer> prices) {
        String json(){var o=new JsonObject();o.addProperty("currency",currency);o.addProperty("editable",editable);var p=new JsonObject();prices.forEach((k,v)->p.addProperty(k.toString(),v));o.add("prices",p);return o.toString();}
        int average(){return BigDecimal.valueOf(prices.values().stream().mapToLong(Integer::longValue).sum()).divide(BigDecimal.valueOf(prices.size()),0,RoundingMode.HALF_UP).intValueExact();}
        boolean equalTo(int target){return prices.values().stream().allMatch(p->p==target);}
    }
    private record Receipt(String state,int target,String before,String task,Instant after) { }
    private static final class ClaimBlocked extends RuntimeException { }
    private static final ConcurrentHashMap<String,Long> NEXT=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Long> COOLDOWN=new ConcurrentHashMap<>();
    private static final Object[] LOCKS=new Object[64];
    static { Arrays.setAll(LOCKS,ignored->new Object()); }
    private final Shop shop; private final ListingTransport client; private final boolean pacing;
    public WbGtinPriceRepair(Shop shop){this(shop,new ListingHttpClient(shop,new OkHttpClient(),Objects.requireNonNull(okhttp3.HttpUrl.parse("https://discounts-prices-api.wildberries.ru/"))),true);}
    public WbGtinPriceRepair(Shop shop,ListingTransport client){this(shop,client,false);}
    WbGtinPriceRepair(Shop source,ListingTransport client,boolean pacing){
        MarketplaceGuard.requireWildberries(source);shop=new Shop(source.getId(),source.getName(),source.getMarketplace(),source.getClientId(),source.getApiKey());this.client=Objects.requireNonNull(client);this.pacing=pacing;
    }
    /** anchor must be stable across restart and unique to the selected GTIN job. */
    public Result repair(String nmId,String anchor,Set<Long> expectedSizes,Runnable guard)throws IOException {return repair(nmId,anchor,expectedSizes,guard,()->{throw new IllegalStateException("WB_PRICE_CONSENT_REQUIRED");});}
    @FunctionalInterface public interface SqlGuard {void check(Connection connection)throws SQLException;}
    public Result repair(String nmId,String anchor,Set<Long> expectedSizes,Runnable guard,Runnable priceConsentGuard)throws IOException {return repair(nmId,anchor,expectedSizes,guard,priceConsentGuard,c->{});}
    public Result repair(String nmId,String anchor,Set<Long> expectedSizes,Runnable guard,Runnable priceConsentGuard,SqlGuard claimGuard)throws IOException {
        Objects.requireNonNull(priceConsentGuard);Objects.requireNonNull(claimGuard);
        if(nmId==null||!nmId.matches("[1-9][0-9]{0,17}")||anchor==null||anchor.isBlank()||anchor.length()>200)throw new IllegalArgumentException("WB_PRICE_REPAIR_ID_INVALID");
        if(expectedSizes==null||expectedSizes.isEmpty()||expectedSizes.size()>1000||expectedSizes.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("WB_PRICE_EXPECTED_SIZES_INVALID");
        var frozenSizes=Set.copyOf(expectedSizes);
        String key=ListingIdentity.fingerprint(shop)+"\n"+nmId;
        synchronized(LOCKS[Math.floorMod(key.hashCode(),LOCKS.length)]){return repairLocked(nmId,anchor,frozenSizes,guard,priceConsentGuard,claimGuard);}
    }
    private Result repairLocked(String nmId,String anchor,Set<Long> expectedSizes,Runnable guard,Runnable priceConsentGuard,SqlGuard claimGuard)throws IOException {
        Objects.requireNonNull(guard);ListingIdentity.require(shop);guard.run();init();
        Receipt prior=load(nmId,anchor);
        if(otherPending(nmId,anchor))return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_OTHER_REQUEST_PENDING");
        if(prior!=null&&(prior.state.equals("FAILED")||prior.state.equals("SUPERSEDED")))return new Result(State.FAILED,null,"WB_PRICE_REJECTED");
        if(prior!=null&&prior.state.equals("RATE_LIMIT")&&prior.after!=null&&Instant.now().isBefore(prior.after))return new Result(State.RATE_LIMIT,Duration.between(Instant.now(),prior.after),"WB_PRICE_RATE_LIMIT");
        // A base/unknown token permits only one price API call per 15 minutes.
        // A fresh verification plus a write cannot safely be performed as a pair:
        // repeated deferred reads would starve the POST, while using an old snapshot
        // could overwrite a seller's newer price. Do not start an unbounded loop.
        // Previously dispatched requests still go through readback without re-sending.
        if(pacing&&!WbPriceApiRateLimiter.supportsGuardedPriceWrite(shop.getApiKey())
                &&(prior==null||prior.state.equals("PLANNED")||prior.state.equals("RATE_LIMIT")))
            return new Result(State.FAILED,null,"WB_PRICE_TOKEN_LIMIT");
        Snapshot current;
        try {current=read(nmId,guard);}catch(ListingRateLimitException rate){return rate(nmId,anchor,prior,rate);}
        if(!current.prices.keySet().equals(expectedSizes)){
            if(prior!=null&&(prior.state.equals("PLANNED")||prior.state.equals("RATE_LIMIT")))transition(nmId,anchor,prior.state,"FAILED",prior.task,null);
            return new Result(State.FAILED,null,"WB_PRICE_SIZE_INCOMPLETE");
        }
        if(prior!=null&&(prior.state.equals("DISPATCHING")||prior.state.equals("WAITING")||prior.state.equals("READY"))){
            if(matches(prior,current)){transition(nmId,anchor,prior.state,"READY",prior.task,null);return new Result(State.READY,null,"");}
            if(prior.state.equals("READY"))return new Result(State.FAILED,null,"WB_PRICE_CHANGED");
            if(prior.task!=null&&!prior.task.isBlank())try {if(taskFailed(prior.task,guard)){transition(nmId,anchor,prior.state,"FAILED",prior.task,null);return new Result(State.FAILED,null,"WB_PRICE_REJECTED");}}catch(ListingRateLimitException rate){return rate(nmId,anchor,prior,rate);}
            return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_READBACK_PENDING");
        }
        int target=current.average();
        if(current.equalTo(target)){
            if(!markReadyWhenUncontested(nmId,anchor,target,current.json()))return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_OTHER_REQUEST_PENDING");
            return new Result(State.READY,null,"");
        }
        // Do not reuse a plan after the seller has changed prices while waiting for a 429.
        if(prior!=null&&prior.before!=null&&!prior.before.isBlank()&&!prior.before.equals(current.json())){transition(nmId,anchor,prior.state,"FAILED",prior.task,null);return new Result(State.FAILED,null,"WB_PRICE_CHANGED");}
        if(current.editable&&current.prices.size()>1000)return new Result(State.FAILED,null,"WB_PRICE_TOO_MANY_SIZES");
        if(!preparePlan(nmId,anchor,target,current.json()))return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_OTHER_REQUEST_PENDING");
        var data=new JsonArray();
        if(current.editable)for(long id:current.prices.keySet()){var entry=new JsonObject();entry.addProperty("nmID",Long.parseLong(nmId));entry.addProperty("sizeID",id);entry.addProperty("price",target);data.add(entry);}
        else {var entry=new JsonObject();entry.addProperty("nmID",Long.parseLong(nmId));entry.addProperty("price",target);data.add(entry);}
        var body=new JsonObject();body.add("data",data);String endpoint=current.editable?"api/v2/upload/task/size":"api/v2/upload/task";
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            await();
            JsonElement response=client.call(endpoint,body,true,()->{guard.run();if(!read(nmId,guard).json().equals(current.json()))throw new IOException("WB_PRICE_CHANGED");await();},()->{guard.run();priceConsentGuard.run();if(!claimProductDispatch(nmId,anchor,target,current.json(),claimGuard))throw new ClaimBlocked();dispatched.set(true);});
            var obj=asObject(response);if(flag(obj,"error")) {transition(nmId,anchor,"DISPATCHING","FAILED","",null);return new Result(State.FAILED,null,"WB_PRICE_REJECTED");}
            var info=obj.has("data")&&obj.get("data").isJsonObject()?obj.getAsJsonObject("data"):new JsonObject();
            String task=text(info,"id");if(!task.matches("[1-9][0-9]{0,18}"))return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_RECEIPT_UNKNOWN");
            transition(nmId,anchor,"DISPATCHING","WAITING",task,null);
            return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_READBACK_PENDING");
        } catch(ClaimBlocked blocked){return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_OTHER_REQUEST_PENDING");}
        catch(ListingRateLimitException rate){return rate(nmId,anchor,new Receipt(dispatched.get()?"DISPATCHING":"PLANNED",target,current.json(),"",null),rate,dispatched.get());}
        catch(ListingRejectedException rejected){transition(nmId,anchor,"DISPATCHING","FAILED","",null);return new Result(State.FAILED,null,"WB_PRICE_REJECTED");}
        catch(IOException|RuntimeException failed){if(!dispatched.get())throw failed;return new Result(State.PENDING,Duration.ofSeconds(30),"WB_PRICE_RECEIPT_UNKNOWN");}
    }
    /** Only read back already-dispatched price requests whose GTIN job was cancelled. */
    public boolean reconcileCancelled()throws IOException {
        ListingIdentity.require(shop);init();var receipts=new ArrayList<String[]>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT DISTINCT r.nm_id,r.anchor FROM wb_gtin_price_repairs r JOIN gtin_sync_jobs j ON j.id=r.anchor JOIN gtin_sync_items i ON i.job_id=j.id AND i.product_id=r.nm_id WHERE r.shop_id=? AND j.shop_id=? AND i.status='CANCELLED' AND r.state IN ('DISPATCHING','WAITING') ORDER BY r.nm_id,r.anchor")){
            p.setInt(1,shop.getId());p.setInt(2,shop.getId());try(var r=p.executeQuery()){while(r.next())receipts.add(new String[]{r.getString(1),r.getString(2)});}
        }catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}
        boolean pending=false;
        for(var entry:receipts){String nm=entry[0],anchor=entry[1];Receipt receipt=load(nm,anchor);if(receipt==null||!Set.of("DISPATCHING","WAITING").contains(receipt.state))continue;
            try {
                Snapshot current=read(nm,()->ListingIdentity.require(shop));
                if(matches(receipt,current)){transition(nm,anchor,receipt.state,"READY",receipt.task,null);}
                else if(receipt.task!=null&&!receipt.task.isBlank()&&taskFailed(receipt.task,()->ListingIdentity.require(shop))){transition(nm,anchor,receipt.state,"FAILED",receipt.task,null);}
                else pending=true;
            }catch(ListingRateLimitException unavailable){return true;}
            catch(IOException unavailable){pending=true;}
        }
        return pending;
    }
    private void markCancelledIssue(Connection c,String nm,String anchor,String code)throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO sync_gtin_issues(item_id,code,updated_at) SELECT id,?,? FROM gtin_sync_items WHERE shop_id=? AND job_id=? AND product_id=? AND status='CANCELLED' ON CONFLICT(item_id) DO UPDATE SET code=excluded.code,updated_at=excluded.updated_at")){
            p.setString(1,code);p.setString(2,Instant.now().toString());p.setInt(3,shop.getId());p.setString(4,anchor);p.setString(5,nm);p.executeUpdate();
        }
    }
    private boolean matches(Receipt receipt,Snapshot current){
        try {var before=JsonParser.parseString(receipt.before).getAsJsonObject();return text(before,"currency").equals(current.currency)&&before.get("editable").getAsBoolean()==current.editable&&before.getAsJsonObject("prices").keySet().equals(current.prices.keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()))&&current.equalTo(receipt.target);}catch(RuntimeException invalid){return false;}
    }
    private Result rate(String nm,String anchor,Receipt prior,ListingRateLimitException error){return rate(nm,anchor,prior,error,false);}
    private Result rate(String nm,String anchor,Receipt prior,ListingRateLimitException error,boolean explicitlyRejectedDispatch){
        if(pacing)COOLDOWN.merge(ListingIdentity.fingerprint(shop),System.currentTimeMillis()+error.retryAfter().toMillis(),Math::max);
        // A 429 before upload is a known rejection. A 429 on readback must not turn
        // a previously submitted request into a retryable mutation.
        if(explicitlyRejectedDispatch)transition(nm,anchor,"DISPATCHING","RATE_LIMIT","",Instant.now().plus(error.retryAfter()));
        else if(prior==null||prior.state.equals("PLANNED")||prior.state.equals("RATE_LIMIT"))recordKnownUnsentRate(nm,anchor,Instant.now().plus(error.retryAfter()));
        return new Result(State.RATE_LIMIT,error.retryAfter(),"WB_PRICE_RATE_LIMIT");
    }
    private Snapshot read(String nm,Runnable guard)throws IOException {
        var response=request("api/v2/list/goods/filter?limit=1&offset=0&filterNmID="+nm,null,guard);var goods=array(data(response),"listGoods");
        if(goods.size()!=1)throw new IOException("WB_PRICE_NOT_FOUND");var item=asObject(goods.get(0));if(!nm.equals(text(item,"nmID")))throw new IOException("WB_PRICE_WRONG_PRODUCT");
        String currency=text(item,"currencyIsoCode4217");if(currency.isBlank())throw new IOException("WB_PRICE_CURRENCY_MISSING");
        if(!item.has("editableSizePrice")||!item.get("editableSizePrice").isJsonPrimitive()||!item.getAsJsonPrimitive("editableSizePrice").isBoolean())throw new IOException("WB_PRICE_SIZE_MODE_MISSING");
        var prices=new TreeMap<Long,Integer>();
        boolean editable=item.get("editableSizePrice").getAsBoolean();
        if(editable){
            boolean finished=false;for(int offset=0;offset<100_000;offset+=1000){var page=array(data(request("api/v2/list/goods/size/nm?limit=1000&offset="+offset+"&nmID="+nm,null,guard)),"listGoods");
                if(page.size()>1000)throw new IOException("WB_PRICE_INCOMPLETE");for(var value:page){var size=asObject(value);if(!nm.equals(text(size,"nmID"))||!currency.equals(text(size,"currencyIsoCode4217")))throw new IOException("WB_PRICE_FOREIGN_SIZE");add(prices,size);}
                if(page.size()<1000){finished=true;break;}}
            if(!finished)throw new IOException("WB_PRICE_PAGE_LIMIT");
        } else for(var value:array(item,"sizes"))add(prices,asObject(value));
        if(prices.isEmpty())throw new IOException("WB_PRICE_MISSING");return new Snapshot(currency,editable,Collections.unmodifiableSortedMap(prices));
    }
    private boolean taskFailed(String task,Runnable guard)throws IOException {
        var response=request("api/v2/history/tasks?uploadID="+task,null,guard);if(!response.has("data")||response.get("data").isJsonNull())return false;
        var value=data(response);
        String status=text(value,"status");return status.equals("4")||status.equals("5")||status.equals("6");
    }
    private JsonObject request(String path,JsonElement body,Runnable guard)throws IOException {ListingIdentity.require(shop);guard.run();await();var value=asObject(client.call(path,body,false));if(flag(value,"error"))throw new IOException("WB_PRICE_READ_REJECTED");return value;}
    private void await()throws IOException {
        if(!pacing)return;String key=ListingIdentity.fingerprint(shop);long reserved;
        synchronized(NEXT){long now=System.currentTimeMillis();reserved=Math.max(now,Math.max(NEXT.getOrDefault(key,now),COOLDOWN.getOrDefault(key,now)));NEXT.put(key,reserved+650);}
        for(;;){long now=System.currentTimeMillis(),cool=COOLDOWN.getOrDefault(key,now);
            if(cool>reserved){synchronized(NEXT){now=System.currentTimeMillis();reserved=Math.max(now,Math.max(NEXT.getOrDefault(key,now),COOLDOWN.getOrDefault(key,now)));NEXT.put(key,reserved+650);}}
            long wait=reserved-System.currentTimeMillis();if(wait<=0)return;
            try{com.vncode.app.shared.RateLimitDeferral.waiting(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(wait));Thread.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("WB_PRICE_WAIT_CANCELLED");}
        }
    }
    private static void add(SortedMap<Long,Integer> prices,JsonObject size)throws IOException {try {long id=new BigDecimal(text(size,"sizeID")).longValueExact();int price=new BigDecimal(text(size,"price")).intValueExact();if(id<=0||price<=0||prices.putIfAbsent(id,price)!=null)throw new ArithmeticException();}catch(RuntimeException invalid){throw new IOException("WB_PRICE_INVALID_SIZE",invalid);}}
    private static JsonObject asObject(JsonElement value)throws IOException {if(value==null||!value.isJsonObject())throw new IOException("WB_PRICE_INVALID_RESPONSE");return value.getAsJsonObject();}
    private static JsonObject data(JsonObject value)throws IOException {if(!value.has("data")||!value.get("data").isJsonObject())throw new IOException("WB_PRICE_INVALID_RESPONSE");return value.getAsJsonObject("data");}
    private static JsonArray array(JsonObject value,String field)throws IOException {if(!value.has(field)||!value.get(field).isJsonArray())throw new IOException("WB_PRICE_INCOMPLETE");return value.getAsJsonArray(field);}
    private static String text(JsonObject value,String key){return value.has(key)&&value.get(key).isJsonPrimitive()?value.get(key).getAsString():"";}
    private static boolean flag(JsonObject value,String key)throws IOException {if(!value.has(key)||!value.get(key).isJsonPrimitive()||!value.getAsJsonPrimitive(key).isBoolean())throw new IOException("WB_PRICE_INVALID_RESPONSE");return value.get(key).getAsBoolean();}
    void init(){try{SqliteWriteTransaction.run(c->{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS wb_gtin_price_repairs(shop_id INTEGER NOT NULL,nm_id TEXT NOT NULL,anchor TEXT NOT NULL,owner TEXT NOT NULL,state TEXT NOT NULL,target INTEGER NOT NULL,before_json TEXT NOT NULL,task_id TEXT NOT NULL,not_before TEXT,PRIMARY KEY(shop_id,nm_id,anchor))");}return null;});}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
    private Receipt load(String nm,String anchor){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT owner,state,target,before_json,task_id,not_before FROM wb_gtin_price_repairs WHERE shop_id=? AND nm_id=? AND anchor=?")){p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);try(var r=p.executeQuery()){if(!r.next())return null;if(!ListingIdentity.fingerprint(shop).equals(r.getString(1)))throw new SecurityException("WB_PRICE_OWNER_CHANGED");String after=r.getString(6);return new Receipt(r.getString(2),r.getInt(3),r.getString(4),r.getString(5),after==null?null:Instant.parse(after));}}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
    private boolean otherPending(String nm,String anchor){try(var c=Database.getConnection()){return blocked(c,nm,anchor,false);}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
    private void requireOwner(Connection c)throws SQLException {if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("WB_PRICE_OWNER_CHANGED");}
    /** Reads every competing receipt; expired 429 is known unsent and can be superseded. */
    private boolean blocked(Connection c,String nm,String anchor,boolean supersede)throws SQLException {
        var expired=new ArrayList<String>();
        try(var p=c.prepareStatement("SELECT anchor,owner,state,not_before FROM wb_gtin_price_repairs WHERE shop_id=? AND nm_id=? AND anchor<>? AND state IN ('RATE_LIMIT','DISPATCHING','WAITING')")){
            p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);
            try(var r=p.executeQuery()){while(r.next()){
                if(!ListingIdentity.fingerprint(shop).equals(r.getString(2)))throw new SecurityException("WB_PRICE_OWNER_CHANGED");
                if(!"RATE_LIMIT".equals(r.getString(3)))return true;
                String until=r.getString(4);if(until==null||Instant.parse(until).isAfter(Instant.now()))return true;expired.add(r.getString(1));
            }}
        }
        if(supersede)try(var p=c.prepareStatement("UPDATE wb_gtin_price_repairs SET state='SUPERSEDED' WHERE shop_id=? AND nm_id=? AND anchor=? AND state='RATE_LIMIT'")){
            for(String old:expired){p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,old);p.addBatch();}p.executeBatch();
        }
        return false;
    }
    /** A PLANNED checkpoint cannot overwrite another JVM's dispatched receipt. */
    boolean preparePlan(String nm,String anchor,int target,String before){try{return SqliteWriteTransaction.run(c->{
        requireOwner(c);if(blocked(c,nm,anchor,false))return false;
        try(var p=c.prepareStatement("INSERT OR IGNORE INTO wb_gtin_price_repairs(shop_id,nm_id,anchor,owner,state,target,before_json,task_id) VALUES(?,?,?,?, 'PLANNED',?,?,'')")){
            p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);p.setString(4,ListingIdentity.fingerprint(shop));p.setInt(5,target);p.setString(6,before);if(p.executeUpdate()==1)return true;
        }
        try(var p=c.prepareStatement("SELECT owner,state,target,before_json,not_before FROM wb_gtin_price_repairs WHERE shop_id=? AND nm_id=? AND anchor=?")){
            p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);try(var r=p.executeQuery()){
                if(!r.next())return false;if(!ListingIdentity.fingerprint(shop).equals(r.getString(1)))throw new SecurityException("WB_PRICE_OWNER_CHANGED");
                String state=r.getString(2),previous=r.getString(4),until=r.getString(5);
                if(!state.equals("PLANNED")&&!state.equals("RATE_LIMIT"))return false;
                if(state.equals("RATE_LIMIT")&&(until==null||Instant.parse(until).isAfter(Instant.now())))return false;
                if(!previous.isBlank()&&(r.getInt(3)!=target||!previous.equals(before)))return false;
            }
        }
        try(var p=c.prepareStatement("UPDATE wb_gtin_price_repairs SET state='PLANNED',target=?,before_json=?,task_id='',not_before=NULL WHERE shop_id=? AND nm_id=? AND anchor=? AND state IN ('PLANNED','RATE_LIMIT')")){
            p.setInt(1,target);p.setString(2,before);p.setInt(3,shop.getId());p.setString(4,nm);p.setString(5,anchor);return p.executeUpdate()==1;
        }
    });}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
    /** Cross-process mutation claim: the conflict check and checkpoint are one SQLite transaction. */
    boolean claimProductDispatch(String nm,String anchor,int target,String before){return claimProductDispatch(nm,anchor,target,before,c->{});}
    boolean claimProductDispatch(String nm,String anchor,int target,String before,SqlGuard claimGuard){try{return SqliteWriteTransaction.run(c->{
        requireOwner(c);claimGuard.check(c);
        try(var p=c.prepareStatement("SELECT owner,state,target,before_json FROM wb_gtin_price_repairs WHERE shop_id=? AND nm_id=? AND anchor=?")){
            p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);try(var r=p.executeQuery()){
                if(!r.next())return false;if(!ListingIdentity.fingerprint(shop).equals(r.getString(1)))throw new SecurityException("WB_PRICE_OWNER_CHANGED");
                if(!"PLANNED".equals(r.getString(2))||r.getInt(3)!=target||!before.equals(r.getString(4)))return false;
            }
        }
        if(blocked(c,nm,anchor,true))return false;
        try(var p=c.prepareStatement("UPDATE wb_gtin_price_repairs SET state='DISPATCHING',task_id='',not_before=NULL WHERE shop_id=? AND nm_id=? AND anchor=? AND owner=? AND state='PLANNED' AND target=? AND before_json=?")){
            p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);p.setString(4,ListingIdentity.fingerprint(shop));p.setInt(5,target);p.setString(6,before);return p.executeUpdate()==1;
        }
    });}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
    private boolean markReadyWhenUncontested(String nm,String anchor,int target,String before){try{return SqliteWriteTransaction.run(c->{
        requireOwner(c);if(blocked(c,nm,anchor,true))return false;
        try(var p=c.prepareStatement("INSERT INTO wb_gtin_price_repairs(shop_id,nm_id,anchor,owner,state,target,before_json,task_id) VALUES(?,?,?,?,'READY',?,?,'') ON CONFLICT(shop_id,nm_id,anchor) DO UPDATE SET state='READY',target=excluded.target,before_json=excluded.before_json,task_id='',not_before=NULL WHERE owner=excluded.owner AND state IN ('PLANNED','RATE_LIMIT','READY')")){
            p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);p.setString(4,ListingIdentity.fingerprint(shop));p.setInt(5,target);p.setString(6,before);return p.executeUpdate()==1;
        }
    });}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
    private void transition(String nm,String anchor,String from,String to,String task,Instant after){try{SqliteWriteTransaction.run(c->{
        requireOwner(c);try(var p=c.prepareStatement("UPDATE wb_gtin_price_repairs SET state=?,task_id=?,not_before=? WHERE shop_id=? AND nm_id=? AND anchor=? AND owner=? AND state=?")){
            p.setString(1,to);p.setString(2,task);p.setString(3,after==null?null:after.toString());p.setInt(4,shop.getId());p.setString(5,nm);p.setString(6,anchor);p.setString(7,ListingIdentity.fingerprint(shop));p.setString(8,from);
            if(p.executeUpdate()==1){String code=switch(to){case "READY"->"WB_PRICE_CANCELLED_APPLIED";case "FAILED"->"WB_PRICE_CANCELLED_REJECTED";case "RATE_LIMIT"->"WB_PRICE_CANCELLED_NOT_SENT";default->"";};if(!code.isBlank())markCancelledIssue(c,nm,anchor,code);}
        }return null;
    });}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
    private void recordKnownUnsentRate(String nm,String anchor,Instant after){try{SqliteWriteTransaction.run(c->{
        requireOwner(c);try(var p=c.prepareStatement("INSERT INTO wb_gtin_price_repairs(shop_id,nm_id,anchor,owner,state,target,before_json,task_id,not_before) VALUES(?,?,?,?,'RATE_LIMIT',0,'','',?) ON CONFLICT(shop_id,nm_id,anchor) DO UPDATE SET state='RATE_LIMIT',not_before=excluded.not_before WHERE owner=excluded.owner AND state IN ('PLANNED','RATE_LIMIT')")){
            p.setInt(1,shop.getId());p.setString(2,nm);p.setString(3,anchor);p.setString(4,ListingIdentity.fingerprint(shop));p.setString(5,after.toString());p.executeUpdate();
        }return null;
    });}catch(SQLException e){throw new IllegalStateException("WB_PRICE_REPAIR_STORAGE",e);}}
}
