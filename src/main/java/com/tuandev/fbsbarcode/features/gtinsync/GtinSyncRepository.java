package com.tuandev.fbsbarcode.features.gtinsync;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

/** Persists only normalized, credential-free data. Every public scoped read verifies ownership. */
public final class GtinSyncRepository {
    public void saveCatalog(int shopId, Marketplace marketplace, List<RegisteredGtin> rows) {
        transaction(c -> {
            requireShop(c,shopId,marketplace);
            try(var d=c.prepareStatement("DELETE FROM gtin_sync_catalog WHERE shop_id=? AND marketplace=?")) {
                d.setInt(1,shopId);d.setString(2,marketplace.name());d.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_catalog VALUES(?,?,?,?)")) {
                for(var row:rows) {
                    if(!RegisteredGtinValidator.isValid(row.gtin())) throw new IllegalArgumentException("invalid_gtin");
                    p.setInt(1,shopId);p.setString(2,marketplace.name());p.setString(3,row.gtin());
                    p.setString(4,GtinJson.GSON.toJson(row));p.addBatch();
                }
                p.executeBatch();
            }
            return null;
        });
    }
    public List<RegisteredGtin> catalog(int shopId, Marketplace marketplace) {
        return transaction(c -> {
            requireShop(c,shopId,marketplace);var result=new ArrayList<RegisteredGtin>();
            try(var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace=? ORDER BY gtin")) {
                p.setInt(1,shopId);p.setString(2,marketplace.name());
                try(var r=p.executeQuery()) { while(r.next()) result.add(GtinJson.GSON.fromJson(r.getString(1),RegisteredGtin.class)); }
            }
            return List.copyOf(result);
        });
    }
    public void saveMapping(ProductKey key, String gtin) {
        if(!RegisteredGtinValidator.isValid(gtin)) throw new IllegalArgumentException("invalid_gtin");
        transaction(c -> {
            requireShop(c,key.shopId(),key.marketplace());
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_mappings VALUES(?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id,variant_id) DO UPDATE SET gtin=excluded.gtin,updated_at=excluded.updated_at")) {
                bindKey(p,key);p.setString(5,gtin);p.setString(6,Instant.now().toString());p.executeUpdate();
            }
            return null;
        });
    }
    public String mapping(ProductKey key) {
        return transaction(c -> {
            requireShop(c,key.shopId(),key.marketplace());
            try(var p=c.prepareStatement("SELECT gtin FROM gtin_sync_mappings WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")) {
                bindKey(p,key);try(var r=p.executeQuery()) {return r.next()?r.getString(1):"";}
            }
        });
    }
    public UUID createJob(List<Preview> previews) {
        if(previews.isEmpty() || previews.size()>1000) throw new IllegalArgumentException("invalid_selection");
        var key=previews.getFirst().before().key();
        if(previews.stream().anyMatch(p -> p.before().key().shopId()!=key.shopId() || p.before().key().marketplace()!=key.marketplace()))
            throw new IllegalArgumentException("mixed_shops");
        String dedup=hash(previews.stream().map(p -> GtinJson.GSON.toJson(List.of(p.before().key(),p.operation(),p.oldGtin(),p.newGtin(),p.before().fingerprint())))
                .sorted().toList().toString());
        return transaction(c -> {
            requireShop(c,key.shopId(),key.marketplace());
            UUID previous=null;
            try(var p=c.prepareStatement("SELECT id FROM gtin_sync_jobs WHERE dedup_key=?")) {
                p.setString(1,dedup);try(var r=p.executeQuery()) {if(r.next()) previous=UUID.fromString(r.getString(1));}
            }
            if(previous!=null) {
                try(var p=c.prepareStatement("SELECT 1 FROM gtin_sync_items WHERE job_id=? AND status NOT IN ('FAILED','CANCELLED') LIMIT 1")) {
                    p.setString(1,previous.toString());try(var r=p.executeQuery()){if(r.next())return previous;}
                }
                // Keep terminal history, but release its confirmation key for an explicit new attempt.
                // Active, ambiguous and succeeded jobs never reach this branch.
                try(var p=c.prepareStatement("UPDATE gtin_sync_jobs SET dedup_key=? WHERE id=?")) {
                    p.setString(1,dedup+":archived:"+previous);p.setString(2,previous.toString());p.executeUpdate();
                }
            }
            var id=UUID.randomUUID();
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_jobs(id,dedup_key,shop_id,marketplace,created_at) VALUES(?,?,?,?,?)")) {
                p.setString(1,id.toString());p.setString(2,dedup);p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,Instant.now().toString());p.executeUpdate();
            }
            for(var preview:previews) {
                var item=UUID.randomUUID();var k=preview.before().key();
                try(var p=c.prepareStatement("INSERT INTO gtin_sync_items(id,job_id,shop_id,marketplace,product_id,variant_id,preview_json,status,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1,item.toString());p.setString(2,id.toString());p.setInt(3,k.shopId());p.setString(4,k.marketplace().name());
                    p.setString(5,k.productId());p.setString(6,k.variantId());p.setString(7,GtinJson.GSON.toJson(preview));p.setString(8,Status.QUEUED.name());p.setString(9,Instant.now().toString());p.executeUpdate();
                }
                event(c,item,Status.QUEUED);
            }
            return id;
        });
    }
    public List<JobItem> pending(int shopId, Marketplace marketplace) {
        return items(shopId,marketplace,false).stream().filter(i -> i.status()!=Status.SUCCEEDED && i.status()!=Status.FAILED && i.status()!=Status.CANCELLED).toList();
    }
    public List<JobItem> history(int shopId, Marketplace marketplace) {return items(shopId,marketplace,false);}
    public List<JobItem> jobItems(UUID jobId) {
        return transaction(c -> {
            requireJob(c,jobId);var result=new ArrayList<JobItem>();
            try(var p=c.prepareStatement("SELECT * FROM gtin_sync_items WHERE job_id=? ORDER BY updated_at,id")) {
                p.setString(1,jobId.toString());try(var r=p.executeQuery()){while(r.next())result.add(item(r));}
            }
            return List.copyOf(result);
        });
    }
    private List<JobItem> items(int shopId, Marketplace marketplace, boolean ignored) {
        return transaction(c -> {
            requireShop(c,shopId,marketplace);var result=new ArrayList<JobItem>();
            try(var p=c.prepareStatement("SELECT * FROM gtin_sync_items WHERE shop_id=? AND marketplace=? ORDER BY updated_at,id")) {
                p.setInt(1,shopId);p.setString(2,marketplace.name());try(var r=p.executeQuery()) {while(r.next()) result.add(item(r));}
            }
            return List.copyOf(result);
        });
    }
    public JobItem item(UUID id) {
        return transaction(c -> {
            try(var p=c.prepareStatement("SELECT * FROM gtin_sync_items WHERE id=?")) {
                p.setString(1,id.toString());try(var r=p.executeQuery()) {
                    if(!r.next()) throw new IllegalArgumentException("unknown_item");
                    requireShop(c,r.getInt("shop_id"),Marketplace.valueOf(r.getString("marketplace")));return item(r);
                }
            }
        });
    }
    public boolean transition(UUID itemId, Status expected, Status next, String remoteTaskId) {
        if(!allowed(expected,next)) throw new IllegalArgumentException("invalid_transition");
        return transaction(c -> {
            var item=itemFor(c,itemId);var key=item.preview().before().key();requireShop(c,key.shopId(),key.marketplace());
            String guard="";
            if(next==Status.VALIDATING || next==Status.SENDING)
                guard=" AND EXISTS(SELECT 1 FROM gtin_sync_jobs j WHERE j.id=gtin_sync_items.job_id AND j.paused=0)";
            if(next==Status.VALIDATING)
                guard+=" AND NOT EXISTS(SELECT 1 FROM gtin_sync_items other WHERE other.id<>gtin_sync_items.id AND other.shop_id=gtin_sync_items.shop_id AND other.marketplace=gtin_sync_items.marketplace AND other.product_id=gtin_sync_items.product_id AND other.status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED'))";
            try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status=?,remote_task_id=?,updated_at=? WHERE id=? AND status=?"+guard)) {
                p.setString(1,next.name());p.setString(2,remoteTaskId==null?item.remoteTaskId():remoteTaskId);p.setString(3,Instant.now().toString());p.setString(4,itemId.toString());p.setString(5,expected.name());
                if(p.executeUpdate()!=1) return false;
                event(c,itemId,next);return true;
            }
        });
    }
    public void recoverInterrupted() {
        transaction(c -> {
            try(var s=c.createStatement()) {
                s.executeUpdate("INSERT INTO gtin_sync_events(item_id,status,created_at) SELECT id,CASE WHEN status='SENDING' THEN 'RECONCILE_REQUIRED' ELSE 'QUEUED' END,strftime('%Y-%m-%dT%H:%M:%fZ','now') FROM gtin_sync_items WHERE status IN ('SENDING','VALIDATING')");
                s.executeUpdate("UPDATE gtin_sync_items SET status=CASE WHEN status='SENDING' THEN 'RECONCILE_REQUIRED' ELSE 'QUEUED' END WHERE status IN ('SENDING','VALIDATING')");
            }
            return null;
        });
    }
    public void pause(UUID jobId, boolean paused) {
        transaction(c -> { requireJob(c,jobId);try(var p=c.prepareStatement("UPDATE gtin_sync_jobs SET paused=? WHERE id=?")) {p.setInt(1,paused?1:0);p.setString(2,jobId.toString());p.executeUpdate();}return null; });
    }
    public boolean paused(UUID jobId) {
        return transaction(c -> {requireJob(c,jobId);try(var p=c.prepareStatement("SELECT paused FROM gtin_sync_jobs WHERE id=?")){p.setString(1,jobId.toString());try(var r=p.executeQuery()){return r.next()&&r.getInt(1)==1;}}});
    }
    private static boolean allowed(Status from, Status to) {
        return switch(from) {
            case QUEUED -> to==Status.VALIDATING || to==Status.CANCELLED;
            case VALIDATING -> to==Status.SENDING || to==Status.FAILED || to==Status.QUEUED || to==Status.CANCELLED;
            case SENDING -> to==Status.AWAITING_VERIFICATION || to==Status.RECONCILE_REQUIRED;
            case AWAITING_VERIFICATION, RECONCILE_REQUIRED -> to==Status.SUCCEEDED || to==Status.FAILED || to==Status.RECONCILE_REQUIRED;
            default -> false;
        };
    }
    private static JobItem itemFor(Connection c, UUID id) throws SQLException {
        try(var p=c.prepareStatement("SELECT * FROM gtin_sync_items WHERE id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("unknown_item");return item(r);}}
    }
    private static JobItem item(ResultSet r) throws SQLException {
        return new JobItem(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("job_id")),GtinJson.GSON.fromJson(r.getString("preview_json"),Preview.class),Status.valueOf(r.getString("status")),r.getString("remote_task_id"));
    }
    private static void requireShop(Connection c,int id,Marketplace marketplace) throws SQLException {
        try(var p=c.prepareStatement("SELECT marketplace FROM shops WHERE id=?")){p.setInt(1,id);try(var r=p.executeQuery()){if(!r.next()||!marketplace.name().equals(r.getString(1)))throw new IllegalArgumentException("shop_mismatch");}}
    }
    private static void requireJob(Connection c,UUID id) throws SQLException {
        try(var p=c.prepareStatement("SELECT shop_id,marketplace FROM gtin_sync_jobs WHERE id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("unknown_job");requireShop(c,r.getInt(1),Marketplace.valueOf(r.getString(2)));}}
    }
    private static void bindKey(PreparedStatement p,ProductKey k) throws SQLException {p.setInt(1,k.shopId());p.setString(2,k.marketplace().name());p.setString(3,k.productId());p.setString(4,k.variantId());}
    private static void event(Connection c,UUID item,Status status) throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO gtin_sync_events(item_id,status,created_at) VALUES(?,?,?)")){p.setString(1,item.toString());p.setString(2,status.name());p.setString(3,Instant.now().toString());p.executeUpdate();}
    }
    static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private interface Work<T>{T run(Connection c)throws SQLException;}
    private static <T> T transaction(Work<T> work) {
        try(var c=Database.getConnection()){c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}catch(Exception e){c.rollback();throw e;}}
        catch(SQLException e){throw new IllegalStateException("gtin_storage_error",e);}
    }
}
