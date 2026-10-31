package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable reviewed correspondence only. This table is not proof of a marketplace write. */
public final class WbOzonLinkRepository {
    public record Link(int wbShopId, String wbNmId, String wbChrtId, String wbOwner,
                       int ozonShopId, String ozonProductId, String ozonOfferId, String ozonSku, String ozonOwner,
                       String gtin, long goodId, String wbCatalogSource, String catalogSource,
                       String wbRevision, String ozonRevision, String evidenceKind, String evidenceDigest,
                       Instant approvedAt) {
        public Link {
            Objects.requireNonNull(approvedAt, "approvedAt");
            gtin = RegisteredGtinProof.canonicalCode(gtin);
        }
    }
    /** Old-scope records expose only their Ozon target as a review hint, never WB or GTIN data. */
    public record ReviewStatus(String ozonProductId,String ozonOfferId,boolean needsReview,Link current) {}

    private record Scope(String wbOwner, String ozonOwner, String wbSource, String ozonSource) {}
    private static final int MAX_BATCH = 100_000;
    private static final Set<String> EVIDENCE = Set.of("EXACT_BARCODE", "EXACT_ARTICLE", "MANUAL_PAIR");

    private static void schema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("""
              CREATE TABLE IF NOT EXISTS wb_ozon_gtin_links(
                wb_shop_id INTEGER NOT NULL, wb_nm_id TEXT NOT NULL, wb_chrt_id TEXT NOT NULL, wb_owner TEXT NOT NULL,
                ozon_shop_id INTEGER NOT NULL, ozon_product_id TEXT NOT NULL, ozon_offer_id TEXT NOT NULL,
                ozon_sku TEXT NOT NULL, ozon_owner TEXT NOT NULL, gtin TEXT NOT NULL, good_id INTEGER NOT NULL,
                wb_catalog_source TEXT NOT NULL, catalog_source TEXT NOT NULL,
                wb_revision TEXT NOT NULL, ozon_revision TEXT NOT NULL,
                evidence_kind TEXT NOT NULL, evidence_digest TEXT NOT NULL, approved_at TEXT NOT NULL,
                PRIMARY KEY(ozon_shop_id, ozon_product_id, ozon_offer_id),
                UNIQUE(ozon_shop_id, gtin),
                FOREIGN KEY(wb_shop_id) REFERENCES shops(id) ON DELETE CASCADE,
                FOREIGN KEY(ozon_shop_id) REFERENCES shops(id) ON DELETE CASCADE)
              """);
            s.execute("CREATE INDEX IF NOT EXISTS wb_ozon_links_source ON wb_ozon_gtin_links(wb_shop_id,wb_nm_id,wb_chrt_id)");
            s.execute("""
              CREATE TABLE IF NOT EXISTS wb_ozon_gtin_dispatch(
                ozon_shop_id INTEGER NOT NULL,ozon_product_id TEXT NOT NULL,ozon_offer_id TEXT NOT NULL,
                job_id TEXT NOT NULL,link_token TEXT NOT NULL,created_at TEXT NOT NULL,
                PRIMARY KEY(ozon_shop_id,ozon_product_id,ozon_offer_id),
                FOREIGN KEY(ozon_shop_id,ozon_product_id,ozon_offer_id)
                    REFERENCES wb_ozon_gtin_links(ozon_shop_id,ozon_product_id,ozon_offer_id) ON DELETE RESTRICT,
                FOREIGN KEY(job_id) REFERENCES catalog_sync_jobs(id) ON DELETE RESTRICT)
              """);
        }
    }

    /** List scoped records. Stored evidence must still be freshly validated before any remote write. */
    public List<Link> list(Shop wb, Shop ozon) {
        Scope scope = scope(wb, ozon);
        try (var c = Database.getConnection()) {
            schema(c);
            try (var p = c.prepareStatement("SELECT * FROM wb_ozon_gtin_links WHERE wb_shop_id=? AND ozon_shop_id=? ORDER BY ozon_product_id,ozon_offer_id")) {
                p.setInt(1, wb.getId()); p.setInt(2, ozon.getId());
                var links = new ArrayList<Link>();
                try (var r = p.executeQuery()) { while (r.next()) { Link link = read(r); requireScope(link, scope); links.add(link); } }
                requireCurrent(wb, ozon, scope);
                return List.copyOf(links);
            }
        } catch (SQLException e) { throw new IllegalStateException("WB_OZON_LINK_STORAGE", e); }
    }

    /** Valid links remain usable when another saved association needs seller re-review. */
    public List<ReviewStatus> listReviewStatus(Shop wb, Shop ozon) {
        Scope scope=scope(wb,ozon);var result=new ArrayList<ReviewStatus>();
        try(var c=Database.getConnection()){
            schema(c);
            try(var p=c.prepareStatement("SELECT * FROM wb_ozon_gtin_links WHERE wb_shop_id=? AND ozon_shop_id=? ORDER BY ozon_product_id,ozon_offer_id")){
                p.setInt(1,wb.getId());p.setInt(2,ozon.getId());
                try(var r=p.executeQuery()){while(r.next()){
                    Link link=read(r);boolean current=scope.wbOwner().equals(link.wbOwner())&&scope.ozonOwner().equals(link.ozonOwner())&&scope.wbSource().equals(link.wbCatalogSource())&&scope.ozonSource().equals(link.catalogSource());
                    result.add(new ReviewStatus(link.ozonProductId(),link.ozonOfferId(),!current,current?link:null));
                }}
            }
            requireCurrent(wb,ozon,scope);return List.copyOf(result);
        }catch(SQLException e){throw new IllegalStateException("WB_OZON_LINK_STORAGE",e);}
    }

    public Optional<Link> find(Shop wb, Shop ozon, String ozonProductId, String ozonOfferId) {
        Scope scope = scope(wb, ozon);
        requireId(ozonProductId); requireId(ozonOfferId);
        try (var c = Database.getConnection()) {
            schema(c);
            try (var p = c.prepareStatement("SELECT * FROM wb_ozon_gtin_links WHERE wb_shop_id=? AND ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=?")) {
                p.setInt(1, wb.getId()); p.setInt(2, ozon.getId()); p.setString(3, ozonProductId); p.setString(4, ozonOfferId);
                try (var r = p.executeQuery()) {
                    if (!r.next()) { requireCurrent(wb, ozon, scope); return Optional.empty(); }
                    Link link = read(r); requireScope(link, scope); requireCurrent(wb, ozon, scope); return Optional.of(link);
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("WB_OZON_LINK_STORAGE", e); }
    }

    /** Opaque version for a fresh review after credential rotation; exposes no stale listing data. */
    public Optional<String> reviewToken(Shop wb, Shop ozon, String ozonProductId, String ozonOfferId) {
        Scope scope = scope(wb, ozon);
        requireId(ozonProductId); requireId(ozonOfferId);
        try (var c = Database.getConnection()) {
            schema(c);
            Link current = findTarget(c, ozon.getId(), ozonProductId, ozonOfferId);
            requireCurrent(wb, ozon, scope);
            if (current == null) return Optional.empty();
            if (current.wbShopId() != wb.getId()) throw new SecurityException("WB_OZON_SHOP_MISMATCH");
            return Optional.of(token(current));
        } catch (SQLException e) { throw new IllegalStateException("WB_OZON_LINK_STORAGE", e); }
    }

    /** Commit reviewed links atomically. A different prior association must be replaced explicitly. */
    public void save(Shop wb, Shop ozon, List<Link> selected) {
        if (selected == null || selected.isEmpty() || selected.size() > MAX_BATCH) throw new IllegalArgumentException("WB_OZON_LINK_INVALID_BATCH");
        List<Link> links = List.copyOf(selected);
        Scope scope = scope(wb, ozon);
        var targets = new HashSet<String>(); var gtins = new HashSet<String>();
        for (Link link : links) {
            requireValid(link, wb, ozon, scope);
            if (!targets.add(link.ozonProductId() + "\u0000" + link.ozonOfferId()) || !gtins.add(link.gtin()))
                throw new IllegalArgumentException("WB_OZON_LINK_DUPLICATE");
        }
        try { SqliteWriteTransaction.run(c -> {
            schema(c); requireDbScope(c, wb, ozon, scope);
            for (Link link : links) {
                Link existing = findTarget(c, link.ozonShopId(), link.ozonProductId(), link.ozonOfferId());
                if (existing != null && !sameAssociation(existing, link)) throw new IllegalStateException("WB_OZON_LINK_REVIEW_CONFLICT");
                if (existing != null && !existing.equals(link) && dispatch(c,existing) != null) throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                write(c, link);
            }
            requireDbScope(c, wb, ozon, scope);
            return null;
        }); } catch (SQLException e) { if ((e.getErrorCode() & 0xff) == 19) throw new IllegalStateException("WB_OZON_GTIN_ALREADY_LINKED", e); throw new IllegalStateException("WB_OZON_LINK_STORAGE", e); }
    }

    /** Replacement is compare-and-swap, for a freshly and separately reviewed association. */
    public void replaceReviewed(Shop wb, Shop ozon, Link expected, Link replacement) {
        Scope scope = scope(wb, ozon);
        requireValid(expected, wb, ozon, scope); requireValid(replacement, wb, ozon, scope);
        if (expected.ozonShopId() != replacement.ozonShopId() || !expected.ozonProductId().equals(replacement.ozonProductId()) || !expected.ozonOfferId().equals(replacement.ozonOfferId()))
            throw new IllegalArgumentException("WB_OZON_LINK_TARGET_CHANGED");
        try { SqliteWriteTransaction.run(c -> {
            schema(c); requireDbScope(c, wb, ozon, scope);
            Link current = findTarget(c, expected.ozonShopId(), expected.ozonProductId(), expected.ozonOfferId());
            if (!expected.equals(current)) throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
            try (var p = c.prepareStatement("DELETE FROM wb_ozon_gtin_links WHERE ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=?")) {
                p.setInt(1, expected.ozonShopId()); p.setString(2, expected.ozonProductId()); p.setString(3, expected.ozonOfferId());
                if (p.executeUpdate() != 1) throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
            }
            write(c, replacement); requireDbScope(c, wb, ozon, scope); return null;
        }); } catch (SQLException e) { if ((e.getErrorCode() & 0xff) == 19) throw new IllegalStateException("WB_OZON_GTIN_ALREADY_LINKED", e); throw new IllegalStateException("WB_OZON_LINK_STORAGE", e); }
    }

    /** Revalidate the complete new association before calling; the opaque token prevents lost updates. */
    public void replaceReviewed(Shop wb, Shop ozon, String expectedToken, Link replacement) {
        Scope scope = scope(wb, ozon); requireValid(replacement, wb, ozon, scope);
        if (!hash(expectedToken)) throw new IllegalArgumentException("WB_OZON_LINK_INVALID_REVIEW_TOKEN");
        try { SqliteWriteTransaction.run(c -> {
            schema(c); requireDbScope(c, wb, ozon, scope);
            Link current = findTarget(c, replacement.ozonShopId(), replacement.ozonProductId(), replacement.ozonOfferId());
            if (current == null || current.wbShopId() != wb.getId() || !token(current).equals(expectedToken)) throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
            try (var p = c.prepareStatement("DELETE FROM wb_ozon_gtin_links WHERE ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=?")) {
                p.setInt(1, replacement.ozonShopId()); p.setString(2, replacement.ozonProductId()); p.setString(3, replacement.ozonOfferId());
                if (p.executeUpdate() != 1) throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
            }
            write(c, replacement); requireDbScope(c, wb, ozon, scope); return null;
        }); } catch (SQLException e) { if ((e.getErrorCode() & 0xff) == 19) throw new IllegalStateException("WB_OZON_GTIN_ALREADY_LINKED", e); throw new IllegalStateException("WB_OZON_LINK_STORAGE", e); }
    }

    /** Atomically confirm this reviewed GTIN plan and save every dispatch pointer before any run. */
    public void confirmAndSaveDispatch(Shop wb, Shop ozon, List<Link> selected, String jobId) {
        if(selected==null||selected.isEmpty()||selected.size()>MAX_BATCH)throw new IllegalArgumentException("WB_OZON_LINK_INVALID_BATCH");
        try{java.util.UUID.fromString(jobId);}catch(RuntimeException invalid){throw new IllegalArgumentException("WB_OZON_DISPATCH_INVALID",invalid);}
        List<Link> links=List.copyOf(selected);Scope scope=scope(wb,ozon);for(Link link:links)requireValid(link,wb,ozon,scope);
        try{SqliteWriteTransaction.run(c->{
            schema(c);requireDbScope(c,wb,ozon,scope);
            boolean confirmed=requireJob(c,ozon,scope,jobId,links,false);
            for(Link link:links){
                if(!link.equals(findTarget(c,link.ozonShopId(),link.ozonProductId(),link.ozonOfferId())))throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
                var prior=dispatch(c,link);
                if(confirmed&&(prior==null||!prior.job().equals(jobId)||!prior.linkToken().equals(token(link))))throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                if(!confirmed&&prior!=null)throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
            }
            if(!confirmed){
                try(var p=c.prepareStatement("UPDATE catalog_sync_jobs SET confirmed=1 WHERE id=? AND shop_id=? AND marketplace='OZON' AND owner=? AND confirmed=0")){
                    p.setString(1,jobId);p.setInt(2,ozon.getId());p.setString(3,scope.ozonOwner());if(p.executeUpdate()!=1)throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                }
                try(var p=c.prepareStatement("INSERT INTO wb_ozon_gtin_dispatch VALUES(?,?,?,?,?,?)")){
                    for(Link link:links){p.setInt(1,link.ozonShopId());p.setString(2,link.ozonProductId());p.setString(3,link.ozonOfferId());p.setString(4,jobId);p.setString(5,token(link));p.setString(6,Instant.now().toString());p.addBatch();}p.executeBatch();
                }
            }
            requireDbScope(c,wb,ozon,scope);return null;
        });}catch(SQLException e){if((e.getErrorCode()&0xff)==19)throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED",e);throw new IllegalStateException("WB_OZON_LINK_STORAGE",e);}
    }

    /** The caller must resume the returned existing job; a changed link is not a new job. */
    public Optional<String> findDispatch(Shop wb, Shop ozon, Link link) {
        Scope scope=scope(wb,ozon);requireValid(link,wb,ozon,scope);
        try(var c=Database.getConnection()){
            schema(c);var current=findTarget(c,link.ozonShopId(),link.ozonProductId(),link.ozonOfferId());
            if(!link.equals(current))throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
            var saved=dispatch(c,link);requireCurrent(wb,ozon,scope);
            if(saved==null)return Optional.empty();
            if(!saved.linkToken().equals(token(link)))throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
            return Optional.of(saved.job());
        }catch(SQLException e){throw new IllegalStateException("WB_OZON_LINK_STORAGE",e);}
    }

    /** Resume always reloads the complete approved cohort, never a subset chosen after restart. */
    public List<Link> linksForDispatch(Shop wb, Shop ozon, String jobId) {
        try { java.util.UUID.fromString(jobId); } catch (RuntimeException invalid) { throw new IllegalArgumentException("WB_OZON_DISPATCH_INVALID", invalid); }
        Scope scope=scope(wb,ozon);
        try(var c=Database.getConnection()) {
            schema(c);var result=new ArrayList<Link>();
            try(var p=c.prepareStatement("""
              SELECT l.*,d.link_token AS dispatch_link_token FROM wb_ozon_gtin_dispatch d
              JOIN wb_ozon_gtin_links l ON l.ozon_shop_id=d.ozon_shop_id AND l.ozon_product_id=d.ozon_product_id AND l.ozon_offer_id=d.ozon_offer_id
              WHERE d.ozon_shop_id=? AND d.job_id=? ORDER BY l.ozon_product_id,l.ozon_offer_id
              """)) {
                p.setInt(1,ozon.getId());p.setString(2,jobId);
                try(var r=p.executeQuery()){while(r.next()){
                    Link link=read(r);requireValid(link,wb,ozon,scope);
                    if(!token(link).equals(r.getString("dispatch_link_token")))throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                    result.add(link);
                }}
            }
            if(result.isEmpty())throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
            requireConfirmedJob(c,ozon,scope,jobId,result);requireCurrent(wb,ozon,scope);return List.copyOf(result);
        }catch(SQLException e){throw new IllegalStateException("WB_OZON_LINK_STORAGE",e);}
    }

    /** Guard generic plan execution, including an unconfirmed preview whose creator crashed. */
    public boolean hasLinksForPlan(Shop ozon, String jobId) {
        if(ozon==null||ozon.getMarketplace()!=Marketplace.OZON)throw new SecurityException("WB_OZON_SHOP_MISMATCH");
        ListingIdentity.require(ozon);String owner=ListingIdentity.fingerprint(ozon);
        try(var c=Database.getConnection()){
            schema(c);
            try(var p=c.prepareStatement("SELECT 1 FROM catalog_sync_jobs WHERE id=? AND shop_id=? AND marketplace='OZON' AND owner=?")){
                p.setString(1,jobId);p.setInt(2,ozon.getId());p.setString(3,owner);try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("WB_OZON_DISPATCH_UNCONFIRMED");}
            }
            boolean found=false;
            try(var rows=c.prepareStatement("SELECT payload FROM catalog_sync_rows WHERE job_id=?");var links=c.prepareStatement("SELECT 1 FROM wb_ozon_gtin_links WHERE ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=? AND gtin=?")){
                rows.setString(1,jobId);try(var r=rows.executeQuery()){while(r.next()){
                    var row=com.vncode.app.features.products.ProductJson.GSON.fromJson(r.getString(1),com.vncode.app.features.catalogsync.CatalogSyncModels.Row.class);
                    if(row==null||row.key()==null||row.catalog()==null||row.key().shopId()!=ozon.getId()||row.key().marketplace()!=Marketplace.OZON)throw new SecurityException("WB_OZON_DISPATCH_WRONG_PLAN");
                    links.setInt(1,ozon.getId());links.setString(2,row.key().productId());links.setString(3,row.key().variantId());links.setString(4,RegisteredGtinProof.canonicalCode(row.catalog().gtin()));
                    try(var matched=links.executeQuery()){if(matched.next()){found=true;break;}}
                }}
            }
            ListingIdentity.require(ozon);return found;
        }catch(SQLException e){throw new IllegalStateException("WB_OZON_LINK_STORAGE",e);}
    }

    /** Check a direct GTIN child operation against the full cross-market approval before POST. */
    public Optional<Link> findCrossTarget(Shop ozon, GtinSyncModels.ProductKey key, String gtin) {
        return Optional.ofNullable(findCrossTargets(ozon,java.util.Map.of(key,gtin)).get(key));
    }

    /** Validate each job cohort once per dispatch, not once per product within that cohort. */
    public java.util.Map<GtinSyncModels.ProductKey,Link> findCrossTargets(Shop ozon,java.util.Map<GtinSyncModels.ProductKey,String> requested) {
        if(ozon==null||ozon.getMarketplace()!=Marketplace.OZON||requested==null||requested.isEmpty()||requested.size()>MAX_BATCH)throw new SecurityException("WB_OZON_SHOP_MISMATCH");
        ListingIdentity.require(ozon);var found=new java.util.LinkedHashMap<GtinSyncModels.ProductKey,Link>();var jobs=new java.util.LinkedHashMap<String,Integer>();
        try(var c=Database.getConnection()){
            schema(c);
            for(var entry:requested.entrySet()){
                var key=entry.getKey();if(key==null||key.marketplace()!=Marketplace.OZON||key.shopId()!=ozon.getId())throw new SecurityException("WB_OZON_SHOP_MISMATCH");
                Link link=findTarget(c,ozon.getId(),key.productId(),key.variantId());
                if(link==null||!RegisteredGtinProof.sameCode(link.gtin(),entry.getValue()))continue;
                Dispatch saved=dispatch(c,link);
                if(saved==null||!saved.linkToken().equals(token(link)))throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                Integer prior=jobs.putIfAbsent(saved.job(),link.wbShopId());if(prior!=null&&prior!=link.wbShopId())throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                found.put(key,link);
            }
        }catch(SQLException e){throw new IllegalStateException("WB_OZON_LINK_STORAGE",e);}
        var approved=new HashSet<Link>();
        for(var job:jobs.entrySet()){
            Shop wb=new com.vncode.app.features.shop.ShopRepository().findById(job.getValue());
            approved.addAll(linksForDispatch(wb,ozon,job.getKey()));
        }
        if(!approved.containsAll(found.values()))throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
        ListingIdentity.require(ozon);return java.util.Map.copyOf(found);
    }

    /** A new attempt is permitted only after explicit review and an entirely terminal old plan. */
    public void clearTerminalDispatch(Shop wb, Shop ozon, List<Link> selected, String jobId, boolean sellerConfirmed) {
        if (!sellerConfirmed) throw new SecurityException("WB_OZON_RETRY_REVIEW_REQUIRED");
        if (selected == null || selected.isEmpty() || selected.size() > MAX_BATCH) throw new IllegalArgumentException("WB_OZON_LINK_INVALID_BATCH");
        List<Link> links=List.copyOf(selected);Scope scope=scope(wb,ozon);
        for(Link link:links)requireValid(link,wb,ozon,scope);
        try { SqliteWriteTransaction.run(c->{
            schema(c);requireDbScope(c,wb,ozon,scope);requireConfirmedJob(c,ozon,scope,jobId,links);
            try(var p=c.prepareStatement("SELECT payload FROM catalog_sync_rows WHERE job_id=?")){
                p.setString(1,jobId);try(var r=p.executeQuery()){while(r.next()){
                    var row=com.vncode.app.features.products.ProductJson.GSON.fromJson(r.getString(1),com.vncode.app.features.catalogsync.CatalogSyncModels.Row.class);
                    boolean blockedUnsent=row.state()==com.vncode.app.features.catalogsync.CatalogSyncModels.State.BLOCKED && (row.gtinJob()==null || row.gtinJob().isBlank());
                    boolean succeeded=row.state()==com.vncode.app.features.catalogsync.CatalogSyncModels.State.SUCCEEDED && childSucceeded(c,row.gtinJob());
                    if(!blockedUnsent && !succeeded)throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                }}
            }
            try(var p=c.prepareStatement("DELETE FROM wb_ozon_gtin_dispatch WHERE ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=? AND job_id=? AND link_token=?")){
                for(Link link:links){
                    if(!link.equals(findTarget(c,link.ozonShopId(),link.ozonProductId(),link.ozonOfferId())))throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
                    p.setInt(1,link.ozonShopId());p.setString(2,link.ozonProductId());p.setString(3,link.ozonOfferId());p.setString(4,jobId);p.setString(5,token(link));
                    if(p.executeUpdate()!=1)throw new IllegalStateException("WB_OZON_DISPATCH_RECONCILE_REQUIRED");
                }
            }
            requireDbScope(c,wb,ozon,scope);return null;
        }); }catch(SQLException e){throw new IllegalStateException("WB_OZON_LINK_STORAGE",e);}
    }
    private static boolean childSucceeded(Connection c,String child)throws SQLException {
        if(child==null||child.isBlank())return true;
        try(var p=c.prepareStatement("SELECT COUNT(*),SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END) FROM gtin_sync_items WHERE job_id=?")){
            p.setString(1,child);try(var r=p.executeQuery()){return r.next()&&r.getInt(1)>0&&r.getInt(1)==r.getInt(2);}
        }
    }

    private record Dispatch(String job,String linkToken) {}
    private static Dispatch dispatch(Connection c, Link link)throws SQLException {
        try(var p=c.prepareStatement("SELECT job_id,link_token FROM wb_ozon_gtin_dispatch WHERE ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=?")){
            p.setInt(1,link.ozonShopId());p.setString(2,link.ozonProductId());p.setString(3,link.ozonOfferId());
            try(var r=p.executeQuery()){return r.next()?new Dispatch(r.getString(1),r.getString(2)):null;}
        }
    }
    private static void requireConfirmedJob(Connection c, Shop ozon, Scope scope, String jobId, List<Link> links)throws SQLException {
        requireJob(c,ozon,scope,jobId,links,true);
    }
    private static boolean requireJob(Connection c, Shop ozon, Scope scope, String jobId, List<Link> links,boolean requireConfirmed)throws SQLException {
        boolean confirmed;
        try(var p=c.prepareStatement("SELECT payload,confirmed FROM catalog_sync_jobs WHERE id=? AND shop_id=? AND marketplace='OZON' AND owner=?")){
            p.setString(1,jobId);p.setInt(2,ozon.getId());p.setString(3,scope.ozonOwner());
            try(var r=p.executeQuery()){
                if(!r.next())throw new SecurityException("WB_OZON_DISPATCH_UNCONFIRMED");
                confirmed=r.getInt(2)==1;
                if(requireConfirmed&&!confirmed)throw new SecurityException("WB_OZON_DISPATCH_UNCONFIRMED");
                var job=com.vncode.app.features.products.ProductJson.GSON.fromJson(r.getString(1),com.vncode.app.features.catalogsync.CatalogSyncModels.Job.class);
                if(job==null||!job.fields().equals(Set.of(com.vncode.app.features.catalogsync.CatalogSyncModels.Field.GTIN)))throw new SecurityException("WB_OZON_DISPATCH_WRONG_PLAN");
            }
        }
        var expected=new java.util.HashMap<String,Link>();for(Link l:links)if(expected.put(l.ozonProductId()+"\u0000"+l.ozonOfferId(),l)!=null)throw new IllegalArgumentException("WB_OZON_LINK_DUPLICATE");
        int rows=0,ready=0;
        try(var p=c.prepareStatement("SELECT payload FROM catalog_sync_rows WHERE job_id=?")){
            p.setString(1,jobId);try(var r=p.executeQuery()){while(r.next()){
                var row=com.vncode.app.features.products.ProductJson.GSON.fromJson(r.getString(1),com.vncode.app.features.catalogsync.CatalogSyncModels.Row.class);
                if(row==null||row.key()==null||row.catalog()==null||row.key().shopId()!=ozon.getId()||row.key().marketplace()!=Marketplace.OZON)throw new SecurityException("WB_OZON_DISPATCH_WRONG_PLAN");
                Link link=expected.remove(row.key().productId()+"\u0000"+row.key().variantId());
                if(link==null||!RegisteredGtinProof.sameCode(link.gtin(),row.catalog().gtin())||!link.catalogSource().equals(row.catalog().source())||!Long.toString(link.goodId()).equals(row.catalog().details().single("@catalog_id")))throw new SecurityException("WB_OZON_DISPATCH_WRONG_PLAN");rows++;if(row.state()==com.vncode.app.features.catalogsync.CatalogSyncModels.State.READY)ready++;
            }}
        }
        if(rows!=links.size()||!expected.isEmpty())throw new SecurityException("WB_OZON_DISPATCH_WRONG_PLAN");
        if(!confirmed&&ready==0)throw new IllegalArgumentException("GTIN_VALIDATION_BLOCKED");
        return confirmed;
    }

    private static Scope scope(Shop wb, Shop ozon) {
        if (wb == null || ozon == null || wb.getMarketplace() != Marketplace.WILDBERRIES || ozon.getMarketplace() != Marketplace.OZON || wb.getId() == ozon.getId()) throw new SecurityException("WB_OZON_SHOP_MISMATCH");
        ListingIdentity.require(wb); ListingIdentity.require(ozon);
        var left = RegisteredGtinProof.current(wb); var right = RegisteredGtinProof.current(ozon);
        RegisteredGtinProof.requireProduction(left); RegisteredGtinProof.requireProduction(right);
        if (!left.inn().equals(right.inn()) || !left.environment().equals(right.environment())) throw new SecurityException("WB_OZON_CATALOG_OWNER_MISMATCH");
        return new Scope(ListingIdentity.fingerprint(wb), ListingIdentity.fingerprint(ozon), left.source(), right.source());
    }
    private static void requireCurrent(Shop wb, Shop ozon, Scope expected) {
        if (!scope(wb, ozon).equals(expected)) throw new SecurityException("WB_OZON_SOURCE_CHANGED");
    }
    private static void requireDbScope(Connection c, Shop wb, Shop ozon, Scope expected) throws SQLException {
        if (!expected.wbOwner().equals(ListingIdentity.fingerprint(c, wb.getId(), Marketplace.WILDBERRIES)) || !expected.ozonOwner().equals(ListingIdentity.fingerprint(c, ozon.getId(), Marketplace.OZON))) throw new SecurityException("WB_OZON_SOURCE_CHANGED");
        requireCurrent(wb, ozon, expected);
    }
    private static void requireScope(Link link, Scope scope) {
        if (!scope.wbOwner().equals(link.wbOwner()) || !scope.ozonOwner().equals(link.ozonOwner()) || !scope.wbSource().equals(link.wbCatalogSource()) || !scope.ozonSource().equals(link.catalogSource())) throw new SecurityException("WB_OZON_LINK_REVIEW_REQUIRED");
    }
    private static void requireValid(Link l, Shop wb, Shop ozon, Scope scope) {
        Objects.requireNonNull(l); requireScope(l, scope);
        if (l.wbShopId() != wb.getId() || l.ozonShopId() != ozon.getId() || !digits(l.wbNmId()) || !digits(l.wbChrtId()) || !digits(l.ozonProductId()) || !digits(l.ozonSku()) || l.goodId() <= 0 || !RegisteredGtinValidator.isValid(l.gtin()) || !hash(l.wbRevision()) || !hash(l.ozonRevision()) || !hash(l.evidenceDigest()) || !EVIDENCE.contains(l.evidenceKind()) || l.approvedAt().isAfter(Instant.now().plusSeconds(300))) throw new IllegalArgumentException("WB_OZON_LINK_INVALID");
        requireId(l.ozonOfferId());
    }
    private static void requireId(String v) { if (v == null || v.isBlank() || v.length() > 512 || !v.equals(v.strip()) || v.indexOf('\u0000') >= 0) throw new IllegalArgumentException("WB_OZON_LINK_INVALID"); }
    private static boolean digits(String v) { return v != null && v.matches("[1-9][0-9]{0,18}"); }
    private static boolean hash(String v) { return v != null && v.matches("[0-9a-f]{64}"); }
    private static boolean sameAssociation(Link a, Link b) { return a.wbShopId()==b.wbShopId() && a.wbNmId().equals(b.wbNmId()) && a.wbChrtId().equals(b.wbChrtId()) && a.ozonSku().equals(b.ozonSku()) && a.gtin().equals(b.gtin()) && a.goodId()==b.goodId() && a.wbOwner().equals(b.wbOwner()) && a.ozonOwner().equals(b.ozonOwner()) && a.wbCatalogSource().equals(b.wbCatalogSource()) && a.catalogSource().equals(b.catalogSource()); }
    private static String token(Link l) {
        return ListingIdentity.hash(new com.google.gson.Gson().toJson(List.of(Integer.toString(l.wbShopId()),l.wbNmId(),l.wbChrtId(),l.wbOwner(),Integer.toString(l.ozonShopId()),l.ozonProductId(),l.ozonOfferId(),l.ozonSku(),l.ozonOwner(),l.gtin(),Long.toString(l.goodId()),l.wbCatalogSource(),l.catalogSource(),l.wbRevision(),l.ozonRevision(),l.evidenceKind(),l.evidenceDigest(),l.approvedAt().toString())));
    }
    private static Link findTarget(Connection c, int shop, String product, String offer) throws SQLException {
        try (var p = c.prepareStatement("SELECT * FROM wb_ozon_gtin_links WHERE ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=?")) {
            p.setInt(1, shop); p.setString(2, product); p.setString(3, offer); try (var r = p.executeQuery()) { return r.next() ? read(r) : null; }
        }
    }
    private static Link read(ResultSet r) throws SQLException {
        return new Link(r.getInt("wb_shop_id"),r.getString("wb_nm_id"),r.getString("wb_chrt_id"),r.getString("wb_owner"),r.getInt("ozon_shop_id"),r.getString("ozon_product_id"),r.getString("ozon_offer_id"),r.getString("ozon_sku"),r.getString("ozon_owner"),r.getString("gtin"),r.getLong("good_id"),r.getString("wb_catalog_source"),r.getString("catalog_source"),r.getString("wb_revision"),r.getString("ozon_revision"),r.getString("evidence_kind"),r.getString("evidence_digest"),Instant.parse(r.getString("approved_at")));
    }
    private static void write(Connection c, Link l) throws SQLException {
        try (var p = c.prepareStatement("""
          INSERT INTO wb_ozon_gtin_links VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          ON CONFLICT(ozon_shop_id,ozon_product_id,ozon_offer_id) DO UPDATE SET
            wb_revision=excluded.wb_revision,ozon_revision=excluded.ozon_revision,
            evidence_kind=excluded.evidence_kind,evidence_digest=excluded.evidence_digest,approved_at=excluded.approved_at
          """)) {
            int i=1; p.setInt(i++,l.wbShopId());p.setString(i++,l.wbNmId());p.setString(i++,l.wbChrtId());p.setString(i++,l.wbOwner());p.setInt(i++,l.ozonShopId());p.setString(i++,l.ozonProductId());p.setString(i++,l.ozonOfferId());p.setString(i++,l.ozonSku());p.setString(i++,l.ozonOwner());p.setString(i++,l.gtin());p.setLong(i++,l.goodId());p.setString(i++,l.wbCatalogSource());p.setString(i++,l.catalogSource());p.setString(i++,l.wbRevision());p.setString(i++,l.ozonRevision());p.setString(i++,l.evidenceKind());p.setString(i++,l.evidenceDigest());p.setString(i,l.approvedAt().toString());p.executeUpdate();
        }
    }
}
