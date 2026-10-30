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
    // Local receipt at the actual transport boundary, not an upstream task identifier.
    private static final String ITEM_SELECT="SELECT i.*,(SELECT e.created_at FROM gtin_sync_events e WHERE e.item_id=i.id AND e.status='DISPATCHED' ORDER BY e.id DESC LIMIT 1) AS dispatched_at FROM gtin_sync_items i";
    public record Change(JobItem item,Status next,String remoteTask,String issue){}
    public void issue(UUID item,String code){write(c->{itemFor(c,item);try(var p=c.prepareStatement("INSERT INTO sync_gtin_issues VALUES(?,?,?) ON CONFLICT(item_id) DO UPDATE SET code=excluded.code,updated_at=excluded.updated_at")){p.setString(1,item.toString());p.setString(2,com.vncode.app.features.sync.SyncRunRepository.code(code));p.setString(3,Instant.now().toString());p.executeUpdate();}return null;});}
    public void issueDetail(UUID item,String detail){
        String safe=com.vncode.app.integration.znack.ZnackSanitizer.message(detail);if(safe.length()>2000)safe=safe.substring(0,2000);final String value=safe;
        write(c->{itemFor(c,item);try(var p=c.prepareStatement("INSERT INTO sync_gtin_issue_details(item_id,detail,updated_at) VALUES(?,?,?) ON CONFLICT(item_id) DO UPDATE SET detail=excluded.detail,updated_at=excluded.updated_at")){p.setString(1,item.toString());p.setString(2,value);p.setString(3,Instant.now().toString());p.executeUpdate();}return null;});
    }
    public Map<ProductKey,String> issueDetails(UUID job){return transaction(c->{requireJob(c,job);var values=new HashMap<ProductKey,String>();try(var p=c.prepareStatement("SELECT i.shop_id,i.marketplace,i.product_id,i.variant_id,d.detail FROM gtin_sync_items i JOIN sync_gtin_issue_details d ON d.item_id=i.id WHERE i.job_id=?")){p.setString(1,job.toString());try(var rows=p.executeQuery()){while(rows.next())values.put(new ProductKey(rows.getInt(1),Marketplace.valueOf(rows.getString(2)),rows.getString(3),rows.getString(4)),rows.getString(5));}}return Map.copyOf(values);});}
    public String issue(UUID item){return transaction(c->{itemFor(c,item);try(var p=c.prepareStatement("SELECT code FROM sync_gtin_issues WHERE item_id=?")){p.setString(1,item.toString());try(var r=p.executeQuery()){return r.next()?r.getString(1):"";}}});}
    public Map<UUID,String> issues(Collection<UUID> ids){
        return transaction(c->{var all=new ArrayList<>(new LinkedHashSet<>(ids));var result=new HashMap<UUID,String>();
            for(int offset=0;offset<all.size();offset+=400){var batch=all.subList(offset,Math.min(offset+400,all.size()));
                try(var p=c.prepareStatement("SELECT i.id,q.code,i.shop_id,i.marketplace FROM gtin_sync_items i JOIN sync_gtin_issues q ON q.item_id=i.id WHERE i.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){
                    for(int n=0;n<batch.size();n++)p.setString(n+1,batch.get(n).toString());try(var r=p.executeQuery()){while(r.next()){requireShop(c,r.getInt(3),Marketplace.valueOf(r.getString(4)));result.put(UUID.fromString(r.getString(1)),r.getString(2));}}
                }
            }return Map.copyOf(result);
        });
    }
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
    /** Bounded IN lists share one read transaction; no per-GTIN connection or full-catalog scan. */
    public Map<String,RegisteredGtin> catalogItems(int shopId,Marketplace marketplace,Collection<String> gtins) {
        return transaction(c->{requireShop(c,shopId,marketplace);var all=new ArrayList<>(new LinkedHashSet<>(gtins));var result=new HashMap<String,RegisteredGtin>();
            for(int offset=0;offset<all.size();offset+=400){var batch=all.subList(offset,Math.min(offset+400,all.size()));
                try(var p=c.prepareStatement("SELECT gtin,data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace=? AND gtin IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){
                    p.setInt(1,shopId);p.setString(2,marketplace.name());for(int i=0;i<batch.size();i++)p.setString(i+3,batch.get(i));
                    try(var r=p.executeQuery()){while(r.next())result.put(r.getString(1),GtinJson.GSON.fromJson(r.getString(2),RegisteredGtin.class));}
                }
            }return Map.copyOf(result);
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
    /** Save an Ozon mapping only if the just-read owned SKU/barcode still match inside the write transaction. */
    public void recordObservedOzonMapping(com.vncode.app.models.Shop shop,ProductKey key,String sku,String gtin,long generation){
        if(shop==null||key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.OZON||shop.getMarketplace()!=Marketplace.OZON||sku==null||sku.isBlank())throw new IllegalArgumentException("shop_mismatch");
        var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop);
        write(c->{
            requireShop(c,key.shopId(),key.marketplace());
            if(!owner.equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,key.shopId(),Marketplace.OZON)))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
            if(!MarketplaceBarcodeCache.currentOzonRead(c,key.shopId(),owner,key.productId(),generation))throw new IllegalStateException("OZON_BARCODE_READ_SUPERSEDED");
            boolean present=false;
            try(var p=c.prepareStatement("SELECT p.sku,b.barcode FROM ozon_products p LEFT JOIN ozon_product_barcodes b ON b.shop_id=p.shop_id AND b.product_id=p.product_id WHERE p.shop_id=? AND p.product_id=? AND p.offer_id=? AND p.archived=0")){
                p.setInt(1,key.shopId());p.setString(2,key.productId());p.setString(3,key.variantId());
                try(var r=p.executeQuery()){while(r.next())if(sku.equals(r.getString(1))&&RegisteredGtinProof.sameCode(gtin,r.getString(2)))present=true;}
            }
            if(!present)throw new IllegalStateException("OZON_MAPPING_READBACK_CHANGED");
            saveVerified(c,key,gtin);
            try(var p=c.prepareStatement("""
                    SELECT m.gtin,m.owner,m.source FROM gtin_sync_verified_mappings m
                    JOIN gtin_sync_catalog g ON g.shop_id=m.shop_id AND g.marketplace=m.marketplace AND g.gtin=m.gtin
                    WHERE m.shop_id=? AND m.marketplace=? AND m.product_id=? AND m.variant_id=?
                      AND json_extract(g.data_json,'$.owner')=m.owner AND json_extract(g.data_json,'$.source')=m.source
                      AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1
                    """)){
                bindKey(p,key);try(var r=p.executeQuery()){
                    if(!r.next()||!RegisteredGtinProof.sameCode(gtin,r.getString(1))||!owner.equals(r.getString(2))||!scope.source().equals(r.getString(3)))throw new IllegalStateException("OZON_MAPPING_PROOF_LOST");
                }
            }
            return null;
        });
    }
    /** Historical readback is durable. It can skip a completed write, never authorize a new one. */
    public record VerifiedMapping(String gtin,Instant verifiedAt,Instant marketplaceReadAt) {
        public boolean stillApplied(Collection<String> barcodes) {
            return barcodes.stream().anyMatch(code->RegisteredGtinProof.sameCode(code,gtin))
                    || marketplaceReadAt==null || marketplaceReadAt.isBefore(verifiedAt);
        }
    }
    public Map<ProductKey,VerifiedMapping> verifiedMappings(com.vncode.app.models.Shop shop) {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop);
        String source=RegisteredGtinProof.source(shop);
        return transaction(c->{
            requireShop(c,shop.getId(),shop.getMarketplace());
            String nativeJoin=shop.getMarketplace()==Marketplace.OZON
                    ? "LEFT JOIN ozon_products p ON p.shop_id=m.shop_id AND p.product_id=m.product_id AND COALESCE(p.offer_id,'')=m.variant_id"
                    : "LEFT JOIN wb_product_cards p ON p.shop_id=m.shop_id AND p.nm_id=CAST(m.product_id AS INTEGER) AND CAST(p.nm_id AS TEXT)=m.product_id";
            String barcodeJoin=shop.getMarketplace()==Marketplace.OZON
                    ? " LEFT JOIN gtin_sync_barcode_observations o ON o.shop_id=m.shop_id AND o.marketplace=m.marketplace AND o.product_id=m.product_id AND o.variant_id=m.variant_id AND o.owner=m.owner"
                    : "";
            String barcodeTime=shop.getMarketplace()==Marketplace.OZON?"o.observed_at":"NULL";
            var result=new HashMap<ProductKey,VerifiedMapping>();
            try(var p=c.prepareStatement("SELECT m.product_id,m.variant_id,m.gtin,m.verified_at,p.synced_at,"+barcodeTime+" FROM gtin_sync_verified_mappings m "+nativeJoin+barcodeJoin+" WHERE m.shop_id=? AND m.marketplace=? AND m.owner=? AND m.source=?")){
                p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,owner);p.setString(4,source);
                try(var rows=p.executeQuery()){while(rows.next()){
                    try {
                        var verified=Instant.parse(rows.getString(4));String read=rows.getString(5);
                        if(verified.isAfter(Instant.now().plusSeconds(300))||!RegisteredGtinValidator.isValid(rows.getString(3)))continue;
                        Instant latest=read==null||read.isBlank()?null:Instant.parse(read);String barcodeRead=rows.getString(6);
                        if(barcodeRead!=null&&!barcodeRead.isBlank()){var observed=Instant.parse(barcodeRead);if(latest==null||observed.isAfter(latest))latest=observed;}
                        result.put(new ProductKey(shop.getId(),shop.getMarketplace(),rows.getString(1),rows.getString(2)),
                                new VerifiedMapping(rows.getString(3),verified,latest));
                    } catch(java.time.format.DateTimeParseException invalid) { /* Unusable evidence never skips a write. */ }
                }}
            }
            return Map.copyOf(result);
        });
    }
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
    public UUID createJob(List<Preview> previews) {return createJob(previews,false);}
    public UUID createJob(List<Preview> previews,boolean wbPriceConsent) {
        if(previews.isEmpty() || previews.size()>GtinBatchAdapter.MAX_PLAN_ROWS) throw new IllegalArgumentException("invalid_selection");
        var key=previews.getFirst().before().key();
        if(previews.stream().anyMatch(p -> p.before().key().shopId()!=key.shopId() || p.before().key().marketplace()!=key.marketplace()))
            throw new IllegalArgumentException("mixed_shops");
        String dedup=hash(previews.stream().map(p -> GtinJson.GSON.toJson(List.of(p.before().key(),p.operation(),p.oldGtin(),p.newGtin(),p.before().fingerprint())))
                .sorted().toList().toString());
        // Serialize outside the writer; a 10,000-member consent stays atomic without
        // retaining the FBS write queue for JSON generation or SQL preparation per row.
        var payloads=previews.stream().map(GtinJson.GSON::toJson).toList();
        return write(c -> {
            requireShop(c,key.shopId(),key.marketplace());
            UUID previous=null;
            try(var p=c.prepareStatement("SELECT id FROM gtin_sync_jobs WHERE dedup_key=?")) {
                p.setString(1,dedup);try(var r=p.executeQuery()) {if(r.next()) previous=UUID.fromString(r.getString(1));}
            }
            if(previous!=null) {
                try(var p=c.prepareStatement("SELECT 1 FROM gtin_sync_items WHERE job_id=? AND status NOT IN ('FAILED','CANCELLED') LIMIT 1")) {
                    p.setString(1,previous.toString());try(var r=p.executeQuery()){if(r.next()){if(wbPriceConsent&&key.marketplace()==Marketplace.WILDBERRIES)grantPriceConsent(c,previous);return previous;}}
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
            String now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_items(id,job_id,shop_id,marketplace,product_id,variant_id,preview_json,status,updated_at) VALUES(?,?,?,?,?,?,?,?,?)");
                var events=c.prepareStatement("INSERT INTO gtin_sync_events(item_id,status,created_at) VALUES(?,?,?)")){
              for(int i=0;i<previews.size();i++) {
                var preview=previews.get(i);var item=UUID.randomUUID();var k=preview.before().key();
                    p.setString(1,item.toString());p.setString(2,id.toString());p.setInt(3,k.shopId());p.setString(4,k.marketplace().name());
                    p.setString(5,k.productId());p.setString(6,k.variantId());p.setString(7,payloads.get(i));p.setString(8,Status.QUEUED.name());p.setString(9,now);p.addBatch();
                events.setString(1,item.toString());events.setString(2,Status.QUEUED.name());events.setString(3,now);events.addBatch();
              }
              p.executeBatch();events.executeBatch();
            }
            if(wbPriceConsent&&key.marketplace()==Marketplace.WILDBERRIES)grantPriceConsent(c,id);
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
            try(var p=c.prepareStatement(ITEM_SELECT+" WHERE i.job_id=? ORDER BY i.updated_at,i.id")) {
                p.setString(1,jobId.toString());try(var r=p.executeQuery()){while(r.next())result.add(item(r));}
            }
            return List.copyOf(result);
        });
    }
    private List<JobItem> items(int shopId, Marketplace marketplace, boolean ignored) {
        return transaction(c -> {
            requireShop(c,shopId,marketplace);var result=new ArrayList<JobItem>();
            try(var p=c.prepareStatement(ITEM_SELECT+" WHERE i.shop_id=? AND i.marketplace=? ORDER BY i.updated_at,i.id")) {
                p.setInt(1,shopId);p.setString(2,marketplace.name());try(var r=p.executeQuery()) {while(r.next()) result.add(item(r));}
            }
            return List.copyOf(result);
        });
    }
    public JobItem item(UUID id) {
        return transaction(c -> {
            try(var p=c.prepareStatement(ITEM_SELECT+" WHERE i.id=?")) {
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
    /** Claim siblings of the same card together, excluding every active item outside this batch.
     * The writer lock is released before validation, rate-limit waits, or network calls. */
    public List<JobItem> claimBatch(List<JobItem> candidates) {
        if(candidates.isEmpty())return List.of();
        return write(c->{var selected=new HashSet<UUID>();candidates.forEach(i->selected.add(i.id()));var blocked=new HashSet<String>();var result=new ArrayList<JobItem>();
            var first=candidates.getFirst().preview().before().key();requireShop(c,first.shopId(),first.marketplace());var cardJobs=new HashMap<String,UUID>();
            try(var p=c.prepareStatement("SELECT id,product_id FROM gtin_sync_items WHERE shop_id=? AND marketplace=? AND status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED')")){
                p.setInt(1,first.shopId());p.setString(2,first.marketplace().name());try(var r=p.executeQuery()){while(r.next())blocked.add(r.getString(2));}
            }
            try(var schema=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE name='product_edit_items'");var rs=schema.executeQuery()){
                if(rs.next())try(var p=c.prepareStatement("SELECT card_id FROM product_edit_items WHERE shop_id=? AND marketplace=? AND state IN ('SENDING','REMOTE_PROCESSING','RECONCILE_REQUIRED')")){
                    p.setInt(1,first.shopId());p.setString(2,first.marketplace().name());try(var r=p.executeQuery()){while(r.next())blocked.add(r.getString(1));}
                }
            }
            for(var candidate:candidates){var item=itemFor(c,candidate.id());var key=item.preview().before().key();
                if(key.shopId()!=first.shopId()||key.marketplace()!=first.marketplace())throw new IllegalArgumentException("mixed_shops");
                if(item.status()!=Status.QUEUED||blocked.contains(key.productId()))continue;
                var cardJob=cardJobs.putIfAbsent(key.productId(),item.jobId());if(cardJob!=null&&!cardJob.equals(item.jobId()))continue;
                try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status='VALIDATING',updated_at=? WHERE id=? AND status='QUEUED' AND EXISTS(SELECT 1 FROM gtin_sync_jobs j WHERE j.id=gtin_sync_items.job_id AND j.paused=0)")){
                    p.setString(1,Instant.now().toString());p.setString(2,item.id().toString());if(p.executeUpdate()==1){event(c,item.id(),Status.VALIDATING);result.add(new JobItem(item.id(),item.jobId(),item.preview(),Status.VALIDATING,item.remoteTaskId(),item.dispatchedAt()));}
                }
            }return List.copyOf(result);
        });
    }
    private void grantPriceConsent(Connection c,UUID id)throws SQLException {
        int shopId;String market;
        try(var p=c.prepareStatement("SELECT shop_id,marketplace FROM gtin_sync_jobs WHERE id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("unknown_job");shopId=r.getInt(1);market=r.getString(2);}}
        if(!market.equals(Marketplace.WILDBERRIES.name()))throw new IllegalArgumentException("WB_PRICE_CONSENT_WRONG_MARKETPLACE");
        try(var p=c.prepareStatement("SELECT COUNT(*),SUM(CASE WHEN status='QUEUED' THEN 1 ELSE 0 END),SUM(CASE WHEN status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED') THEN 1 ELSE 0 END) FROM gtin_sync_items WHERE job_id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){r.next();if(r.getInt(2)==0||r.getInt(3)>0)throw new IllegalStateException("WB_PRICE_CONSENT_JOB_ACTIVE");}}
        String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shopId,Marketplace.WILDBERRIES);
        try(var p=c.prepareStatement("SELECT owner FROM wb_gtin_price_consents WHERE job_id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){if(r.next()){if(!owner.equals(r.getString(1)))throw new SecurityException("WB_PRICE_OWNER_CHANGED");return;}}}
        try(var p=c.prepareStatement("INSERT INTO wb_gtin_price_consents(job_id,owner,accepted_at) VALUES(?,?,?)")){p.setString(1,id.toString());p.setString(2,owner);p.setString(3,Instant.now().toString());p.executeUpdate();}
    }
    public void grantWbPriceConsent(UUID id){write(c->{grantPriceConsent(c,id);return null;});}
    public boolean hasWbPriceConsent(UUID id){return transaction(c->{try(var p=c.prepareStatement("SELECT c.owner,j.shop_id,j.marketplace FROM wb_gtin_price_consents c JOIN gtin_sync_jobs j ON j.id=c.job_id WHERE j.id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){return r.next()&&Marketplace.WILDBERRIES.name().equals(r.getString(3))&&r.getString(1).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,r.getInt(2),Marketplace.WILDBERRIES));}}});}
    public void requireWbPriceConsent(List<JobItem> items){
        requireValidatingBatch(items);UUID id=items.getFirst().jobId();
        if(items.stream().anyMatch(i->!i.jobId().equals(id)))throw new IllegalArgumentException("mixed_jobs");
        if(!hasWbPriceConsent(id))throw new IllegalStateException("WB_PRICE_CONSENT_REQUIRED");
    }
    /** Called under the same write transaction that marks the price request DISPATCHING. */
    public void requirePriceDispatch(Connection c,List<JobItem> items)throws SQLException {
        if(items.isEmpty())throw new IllegalArgumentException("empty_selection");
        var first=items.getFirst();var key=first.preview().before().key();
        if(key.marketplace()!=Marketplace.WILDBERRIES)throw new IllegalArgumentException("WB_PRICE_CONSENT_WRONG_MARKETPLACE");
        requireShop(c,key.shopId(),key.marketplace());
        try(var p=c.prepareStatement("SELECT j.paused,j.shop_id,j.marketplace,c.owner FROM gtin_sync_jobs j LEFT JOIN wb_gtin_price_consents c ON c.job_id=j.id WHERE j.id=?")){
            p.setString(1,first.jobId().toString());try(var r=p.executeQuery()){
                if(!r.next()||r.getInt(1)!=0||r.getInt(2)!=key.shopId()||!key.marketplace().name().equals(r.getString(3)))throw new IllegalStateException("GTIN_BATCH_PAUSED");
                if(r.getString(4)==null)throw new IllegalStateException("WB_PRICE_CONSENT_REQUIRED");
                if(!r.getString(4).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,key.shopId(),key.marketplace())))throw new SecurityException("WB_PRICE_OWNER_CHANGED");
            }
        }
        for(var expected:items){var item=itemFor(c,expected.id());if(!item.jobId().equals(first.jobId())||!item.preview().before().key().equals(expected.preview().before().key())||!item.preview().newGtin().equals(expected.preview().newGtin())||item.status()!=Status.VALIDATING)throw new IllegalStateException("GTIN_BATCH_CHECKPOINT_CHANGED");}
    }
    /** Pause and cancel GTIN atomically with the price-dispatch checkpoint. */
    public void cancelKnownUnsent(UUID job){write(c->{requireJob(c,job);
        try(var p=c.prepareStatement("UPDATE gtin_sync_jobs SET paused=1 WHERE id=?")){p.setString(1,job.toString());p.executeUpdate();}
        var targets=new ArrayList<JobItem>();try(var p=c.prepareStatement(ITEM_SELECT+" WHERE i.job_id=? AND i.status IN ('QUEUED','VALIDATING')")){p.setString(1,job.toString());try(var r=p.executeQuery()){while(r.next())targets.add(item(r));}}
        for(var item:targets){var key=item.preview().before().key();String priceIssue="";
            if(key.marketplace()==Marketplace.WILDBERRIES)try(var p=c.prepareStatement("SELECT state,target,before_json FROM wb_gtin_price_repairs WHERE shop_id=? AND nm_id=? AND anchor=?")){
                p.setInt(1,key.shopId());p.setString(2,key.productId());p.setString(3,job.toString());try(var r=p.executeQuery()){if(r.next()){
                    String state=r.getString(1);priceIssue=switch(state){case "DISPATCHING","WAITING"->"WB_PRICE_REQUEST_IN_FLIGHT";case "FAILED"->"WB_PRICE_CANCELLED_REJECTED";case "RATE_LIMIT"->"WB_PRICE_CANCELLED_NOT_SENT";default->"";};
                    if(state.equals("READY")){try{var values=com.google.gson.JsonParser.parseString(r.getString(3)).getAsJsonObject().getAsJsonObject("prices");int target=r.getInt(2);if(values.entrySet().stream().anyMatch(entry->entry.getValue().getAsInt()!=target))priceIssue="WB_PRICE_CANCELLED_APPLIED";}catch(RuntimeException unavailable){priceIssue="WB_PRICE_CANCELLED_STATUS_UNKNOWN";}}
                }}
            }
            try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status='CANCELLED',updated_at=? WHERE id=? AND status IN ('QUEUED','VALIDATING')")){p.setString(1,Instant.now().toString());p.setString(2,item.id().toString());if(p.executeUpdate()!=1)continue;}
            if(!priceIssue.isBlank())putIssue(c,item.id(),priceIssue);event(c,item.id(),Status.CANCELLED);
        }return null;});}
    /** Guard a price preflight before any GTIN request is marked as sent. */
    public void requireValidatingBatch(List<JobItem> items) {
        if(items.isEmpty())throw new IllegalArgumentException("empty_selection");
        transaction(c->{var jobs=new HashSet<UUID>();for(var expected:items){var item=itemFor(c,expected.id());var key=item.preview().before().key();requireShop(c,key.shopId(),key.marketplace());
                if(!item.jobId().equals(expected.jobId())||!key.equals(expected.preview().before().key())||!item.preview().newGtin().equals(expected.preview().newGtin())||item.status()!=Status.VALIDATING)throw new IllegalStateException("GTIN_BATCH_CHECKPOINT_CHANGED");jobs.add(item.jobId());}
            for(var id:jobs)try(var p=c.prepareStatement("SELECT paused FROM gtin_sync_jobs WHERE id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){if(!r.next()||r.getInt(1)!=0)throw new IllegalStateException("GTIN_BATCH_PAUSED");}}
            return null;
        });
    }
    /** Atomic request checkpoint. A concurrent pause/cancellation prevents the entire request. */
    public boolean sendingBatch(List<JobItem> items) {
        if(items.isEmpty())return false;
        return write(c->{for(var expected:items){var item=itemFor(c,expected.id());if(item.status()!=Status.VALIDATING)return false;
                try(var p=c.prepareStatement("SELECT paused FROM gtin_sync_jobs WHERE id=?")){p.setString(1,item.jobId().toString());try(var r=p.executeQuery()){if(!r.next()||r.getInt(1)!=0)return false;}}
            }
            try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status='SENDING',updated_at=? WHERE id=? AND status='VALIDATING'")){
                for(var item:items){p.setString(1,Instant.now().toString());p.setString(2,item.id().toString());p.addBatch();}p.executeBatch();
            }for(var item:items)event(c,item.id(),Status.SENDING);return true;
        });
    }
    /** Checkpoints/readback results in short transactions; no connection/commit per GTIN. */
    public List<JobItem> applyBatch(List<Change> changes){
        var result=new ArrayList<JobItem>();
        for(int offset=0;offset<changes.size();offset+=250){var part=changes.subList(offset,Math.min(offset+250,changes.size()));
            result.addAll(write(c->{var completed=new ArrayList<JobItem>();
                for(var change:part){var expected=change.item();if(change.next()==Status.SENDING||change.next()==Status.VALIDATING||!allowed(expected.status(),change.next()))throw new IllegalArgumentException("invalid_transition");
                    var actual=itemFor(c,expected.id());if(actual.status()!=expected.status())continue;var key=actual.preview().before().key();requireShop(c,key.shopId(),key.marketplace());String remote=change.remoteTask()==null?actual.remoteTaskId():change.remoteTask();
                    try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status=?,remote_task_id=?,updated_at=? WHERE id=? AND status=?")){
                        p.setString(1,change.next().name());p.setString(2,remote);p.setString(3,Instant.now().toString());p.setString(4,actual.id().toString());p.setString(5,expected.status().name());if(p.executeUpdate()!=1)continue;
                    }
                    if(change.issue()!=null)putIssue(c,actual.id(),change.issue());if(change.next()==Status.SUCCEEDED)saveVerified(c,key,actual.preview().newGtin());event(c,actual.id(),change.next());completed.add(new JobItem(actual.id(),actual.jobId(),actual.preview(),change.next(),remote,actual.dispatchedAt()));
                }return completed;
            }));
        }return List.copyOf(result);
    }
    public void requireSendingBatch(List<JobItem> items) {
        write(c->{var jobs=new HashSet<UUID>();for(var expected:items){var item=itemFor(c,expected.id());var key=item.preview().before().key();requireShop(c,key.shopId(),key.marketplace());if(!item.jobId().equals(expected.jobId())||!key.equals(expected.preview().before().key())||item.status()!=Status.SENDING)throw new IllegalStateException("GTIN_BATCH_CHECKPOINT_CHANGED");jobs.add(item.jobId());}
            for(var id:jobs)try(var p=c.prepareStatement("SELECT paused FROM gtin_sync_jobs WHERE id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){if(!r.next()||r.getInt(1)!=0)throw new IllegalStateException("GTIN_BATCH_PAUSED");}}
            String now=Instant.now().toString();try(var p=c.prepareStatement("INSERT INTO gtin_sync_events(item_id,status,created_at) VALUES(?,'DISPATCHED',?)")){for(var item:items){p.setString(1,item.id().toString());p.setString(2,now);p.addBatch();}p.executeBatch();}return null;
        });
    }
    /** Only for an adapter's proven pre-dispatch failure, never a timeout or response loss. */
    public void releaseUnsentBatch(List<JobItem> items,String code,boolean pause) {
        write(c->{var jobs=new HashSet<UUID>();for(var expected:items){var item=itemFor(c,expected.id());
                if(item.status()!=Status.SENDING&&item.status()!=Status.VALIDATING)continue;
                if(item.remoteTaskId()!=null&&!item.remoteTaskId().isBlank())throw new IllegalStateException("already_submitted");
                try(var p=c.prepareStatement("UPDATE gtin_sync_items SET status='QUEUED',updated_at=? WHERE id=? AND status=?")){
                    p.setString(1,Instant.now().toString());p.setString(2,item.id().toString());p.setString(3,item.status().name());p.executeUpdate();
                }
                putIssue(c,item.id(),code);event(c,item.id(),Status.QUEUED);jobs.add(item.jobId());
            }
            if(pause)for(var job:jobs)try(var p=c.prepareStatement("UPDATE gtin_sync_jobs SET paused=1 WHERE id=?")){p.setString(1,job.toString());p.executeUpdate();}return null;
        });
    }
    private static void putIssue(Connection c,UUID id,String code)throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO sync_gtin_issues VALUES(?,?,?) ON CONFLICT(item_id) DO UPDATE SET code=excluded.code,updated_at=excluded.updated_at")){
            p.setString(1,id.toString());p.setString(2,com.vncode.app.features.sync.SyncRunRepository.code(code));p.setString(3,Instant.now().toString());p.executeUpdate();
        }
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
            case SENDING -> to==Status.AWAITING_VERIFICATION || to==Status.RECONCILE_REQUIRED || to==Status.FAILED;
            case AWAITING_VERIFICATION, RECONCILE_REQUIRED -> to==Status.SUCCEEDED || to==Status.FAILED || to==Status.RECONCILE_REQUIRED;
            default -> false;
        };
    }
    private static JobItem itemFor(Connection c, UUID id) throws SQLException {
        try(var p=c.prepareStatement(ITEM_SELECT+" WHERE i.id=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("unknown_item");return item(r);}}
    }
    private static JobItem item(ResultSet r) throws SQLException {
        return new JobItem(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("job_id")),GtinJson.GSON.fromJson(r.getString("preview_json"),Preview.class),Status.valueOf(r.getString("status")),r.getString("remote_task_id"),r.getString("dispatched_at")==null?null:Instant.parse(r.getString("dispatched_at")));
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
