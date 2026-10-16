package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** Persists only normalized, credential-free data. Every public scoped read verifies ownership. */
public final class GtinSyncRepository {
    public void issue(UUID item,String code){write(c->{itemFor(c,item);try(var p=c.prepareStatement("INSERT INTO sync_gtin_issues VALUES(?,?,?) ON CONFLICT(item_id) DO UPDATE SET code=excluded.code,updated_at=excluded.updated_at")){p.setString(1,item.toString());p.setString(2,com.vncode.app.features.sync.SyncRunRepository.code(code));p.setString(3,Instant.now().toString());p.executeUpdate();}return null;});}
    public String issue(UUID item){return transaction(c->{itemFor(c,item);try(var p=c.prepareStatement("SELECT code FROM sync_gtin_issues WHERE item_id=?")){p.setString(1,item.toString());try(var r=p.executeQuery()){return r.next()?r.getString(1):"";}}});}
    public void saveCatalog(int shopId, Marketplace marketplace, List<RegisteredGtin> rows) {
        write(c -> {
            requireShop(c,shopId,marketplace);
            try(var d=c.prepareStatement("DELETE FROM gtin_sync_catalog WHERE shop_id=? AND marketplace=?")) {
                d.setInt(1,shopId);d.setString(2,marketplace.name());d.executeUpdate();
            }
            String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shopId,marketplace);
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_catalog VALUES(?,?,?,?)")) {
                for(var row:rows) {
                    if(!RegisteredGtinValidator.isValid(row.gtin())) throw new IllegalArgumentException("invalid_gtin");
                    p.setInt(1,shopId);p.setString(2,marketplace.name());p.setString(3,row.gtin());
                    var data=GtinJson.GSON.toJsonTree(row).getAsJsonObject();
                    data.addProperty("owner",owner);
                    p.setString(4,GtinJson.GSON.toJson(data));p.addBatch();
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
    /** Merge a fully completed selected-card observation; untouched GTINs retain their cache. */
    public void mergeCatalog(int shopId,Marketplace marketplace,List<RegisteredGtin> rows){
        write(c->{requireShop(c,shopId,marketplace);String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shopId,marketplace);
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_catalog VALUES(?,?,?,?) ON CONFLICT(shop_id,marketplace,gtin) DO UPDATE SET data_json=excluded.data_json")){
                var seen=new HashSet<String>();for(var row:rows){if(!RegisteredGtinValidator.isValid(row.gtin())||!seen.add(row.gtin()))throw new IllegalArgumentException("invalid_gtin");var data=GtinJson.GSON.toJsonTree(row).getAsJsonObject();data.addProperty("owner",owner);p.setInt(1,shopId);p.setString(2,marketplace.name());p.setString(3,row.gtin());p.setString(4,GtinJson.GSON.toJson(data));p.addBatch();}p.executeBatch();
            }return null;
        });
    }
    public RegisteredGtin catalogItem(int shopId,Marketplace marketplace,String gtin) {
        return transaction(c->{requireShop(c,shopId,marketplace);
            try(var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace=? AND gtin=?")){
                p.setInt(1,shopId);p.setString(2,marketplace.name());p.setString(3,gtin);
                try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("unverified_catalog_gtin");return GtinJson.GSON.fromJson(r.getString(1),RegisteredGtin.class);}
            }
        });
    }
    public void saveMapping(ProductKey key, String gtin) {
        if(!RegisteredGtinValidator.isValid(gtin)) throw new IllegalArgumentException("invalid_gtin");
        write(c -> {
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
    /** A completed remote readback, never a proposed matching rule, authorizes KIZ resolution. */
    public void recordVerifiedMapping(ProductKey key,String gtin){write(c->{requireShop(c,key.shopId(),key.marketplace());saveVerified(c,key,gtin);return null;});}
    private static void saveVerified(Connection c,ProductKey key,String gtin)throws SQLException {
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,key.shopId(),key.marketplace()),source;
        try(var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace=? AND gtin=?")){
            p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,gtin);
            try(var r=p.executeQuery()){
                if(!r.next())return;var data=com.google.gson.JsonParser.parseString(r.getString(1)).getAsJsonObject();
                if(!data.has("owner")||!owner.equals(data.get("owner").getAsString())||!data.get("published").getAsBoolean()||!data.get("tradeUnit").getAsBoolean()||!RegisteredGtinValidator.isValid(gtin))return;
                source=data.get("source").getAsString();if(!source.startsWith("National Catalog@"))return;
            }
        }
        try(var p=c.prepareStatement("INSERT INTO gtin_sync_verified_mappings VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id,variant_id) DO UPDATE SET gtin=excluded.gtin,owner=excluded.owner,source=excluded.source,verified_at=excluded.verified_at")){
            bindKey(p,key);p.setString(5,gtin);p.setString(6,owner);p.setString(7,source);p.setString(8,Instant.now().toString());p.executeUpdate();
        }
    }
    public UUID createJob(List<Preview> previews) {
        if(previews.isEmpty() || previews.size()>1000) throw new IllegalArgumentException("invalid_selection");
        var key=previews.getFirst().before().key();
        if(previews.stream().anyMatch(p -> p.before().key().shopId()!=key.shopId() || p.before().key().marketplace()!=key.marketplace()))
            throw new IllegalArgumentException("mixed_shops");
        String dedup=hash(previews.stream().map(p -> GtinJson.GSON.toJson(List.of(p.before().key(),p.operation(),p.oldGtin(),p.newGtin(),p.before().fingerprint())))
                .sorted().toList().toString());
        return write(c -> {
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
        return write(c -> {
            var item=itemFor(c,itemId);var key=item.preview().before().key();requireShop(c,key.shopId(),key.marketplace());
            String guard="";
            if(next==Status.VALIDATING || next==Status.SENDING)
                guard=" AND EXISTS(SELECT 1 FROM gtin_sync_jobs j WHERE j.id=gtin_sync_items.job_id AND j.paused=0)";
            if(next==Status.VALIDATING)
                guard+=" AND NOT EXISTS(SELECT 1 FROM gtin_sync_items other WHERE other.id<>gtin_sync_items.id AND other.shop_id=gtin_sync_items.shop_id AND other.marketplace=gtin_sync_items.marketplace AND other.product_id=gtin_sync_items.product_id AND other.status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED'))";
            if(next==Status.VALIDATING || next==Status.SENDING) {
                try(var schema=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE name='product_edit_items'");var rs=schema.executeQuery()) {
                    if(rs.next())guard+=" AND NOT EXISTS(SELECT 1 FROM product_edit_items e WHERE e.shop_id=gtin_sync_items.shop_id AND e.marketplace=gtin_sync_items.marketplace AND e.card_id=gtin_sync_items.product_id AND e.state IN ('SENDING','REMOTE_PROCESSING','RECONCILE_REQUIRED'))";
                }
            }
            try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status=?,remote_task_id=?,updated_at=? WHERE id=? AND status=?"+guard)) {
                p.setString(1,next.name());p.setString(2,remoteTaskId==null?item.remoteTaskId():remoteTaskId);p.setString(3,Instant.now().toString());p.setString(4,itemId.toString());p.setString(5,expected.name());
                if(p.executeUpdate()!=1) return false;
                if(next==Status.SUCCEEDED)saveVerified(c,key,item.preview().newGtin());
                event(c,itemId,next);return true;
            }
        });
    }
    public void recoverInterrupted() {
        write(c -> {
            try(var s=c.createStatement()) {
                s.executeUpdate("INSERT INTO gtin_sync_events(item_id,status,created_at) SELECT id,CASE WHEN status='SENDING' THEN 'RECONCILE_REQUIRED' ELSE 'QUEUED' END,strftime('%Y-%m-%dT%H:%M:%fZ','now') FROM gtin_sync_items WHERE status IN ('SENDING','VALIDATING')");
                s.executeUpdate("UPDATE gtin_sync_items SET status=CASE WHEN status='SENDING' THEN 'RECONCILE_REQUIRED' ELSE 'QUEUED' END WHERE status IN ('SENDING','VALIDATING')");
            }
            return null;
        });
    }
    /** Called only before submit: a denied lease is known-unsent, never a remote ambiguity. */
    void pauseKnownUnsent(UUID itemId) {
        write(c -> {
            var item=itemFor(c,itemId);var key=item.preview().before().key();requireShop(c,key.shopId(),key.marketplace());
            if(item.status()!=Status.VALIDATING && item.status()!=Status.SENDING)return null;
            if(item.remoteTaskId()!=null && !item.remoteTaskId().isBlank())throw new IllegalStateException("already_submitted");
            try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status='QUEUED',updated_at=? WHERE id=? AND status=? AND COALESCE(remote_task_id,'')=''")) {
                p.setString(1,Instant.now().toString());p.setString(2,itemId.toString());p.setString(3,item.status().name());
                if(p.executeUpdate()!=1)return null;
            }
            event(c,itemId,Status.QUEUED);
            try(var p=c.prepareStatement("UPDATE gtin_sync_jobs SET paused=1 WHERE id=?")) {p.setString(1,item.jobId().toString());p.executeUpdate();}
            return null;
        });
    }
    public void pause(UUID jobId, boolean paused) {
        write(c -> { requireJob(c,jobId);try(var p=c.prepareStatement("UPDATE gtin_sync_jobs SET paused=? WHERE id=?")) {p.setInt(1,paused?1:0);p.setString(2,jobId.toString());p.executeUpdate();}return null; });
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
    private static <T> T write(Work<T> work) {
        try { return com.vncode.app.config.SqliteWriteTransaction.run(work::run); }
        catch(SQLException e){throw new IllegalStateException("gtin_storage_error",e);}
    }
    private static <T> T transaction(Work<T> work) {
        try(var c=Database.getConnection()){c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}catch(Exception e){c.rollback();throw e;}}
        catch(SQLException e){throw new IllegalStateException("gtin_storage_error",e);}
    }
}
