package com.vncode.app.features.kiz;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonCatalogRepository;
import com.vncode.app.integration.ozon.OzonShopBrand;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.registration.RegistrationReviewedValues;
import com.vncode.app.models.Shop;
import java.math.BigInteger;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.sql.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Locally rechecks a seller-reviewed WB/Ozon pair; never asserts a marketplace barcode write. */
public final class KizCrossMarketplaceProof {
    public record Variant(String productId,String sku) {}
    public record RefreshResult(int attempted,int verified,Map<String,String> issues){public RefreshResult{issues=Map.copyOf(issues);}}
    private record OzonObservation(String productId,String offerId,String sku,String owner,String source,String gtin,long goodId,
            String revision,String title,String article,String color,String size,List<String> barcodes,Instant checkedAt,GtinSyncModels.RegisteredGtin catalog){
        OzonObservation{barcodes=List.copyOf(barcodes);}
    }
    private record WbObservation(String productId,String variantId,String owner,String source,String gtin,long goodId,String revision,
            String title,String article,String size,String russianSize,List<String> barcodes,Instant checkedAt){
        WbObservation{barcodes=List.copyOf(barcodes);}
    }
    interface ObservationReader {
        ProductSnapshot wb(Shop shop,ProductKey key)throws Exception;
        com.vncode.app.integration.znack.registration.OzonExistingCardLinkService.Review ozon(Shop shop,GtinSyncModels.ProductKey key,String gtin)throws Exception;
    }
    private KizCrossMarketplaceProof() {}
    private static final int REFRESH_LINK_LIMIT=10;
    private static final long REFRESH_BUDGET_NANOS=Duration.ofSeconds(10).toNanos();
    private static final java.util.concurrent.atomic.AtomicLong REFRESH_CURSOR=new java.util.concurrent.atomic.AtomicLong();

    /** Explicit seller print/accept action only. Reads seller sources, never purchases or writes to a marketplace. */
    public static RefreshResult refreshMissing(Shop ozon,Collection<String> skus,BooleanSupplier cancelled)throws IOException {
        return refreshMissing(ozon,skus,cancelled,new ObservationReader(){
            public ProductSnapshot wb(Shop shop,ProductKey key)throws Exception{return new com.vncode.app.integration.wb.WbProductSnapshotReader().readForIdentityProof(shop,key);}
            public com.vncode.app.integration.znack.registration.OzonExistingCardLinkService.Review ozon(Shop shop,GtinSyncModels.ProductKey key,String gtin)throws Exception{return new com.vncode.app.integration.znack.registration.OzonExistingCardLinkService().read(shop,key,gtin);}
        });
    }
    @FunctionalInterface interface RefreshBatch {RefreshResult run(Collection<String> skus)throws IOException;}
    /** Background reconciliation only: no purchase, order acceptance, or marketplace mutation. */
    public static RefreshResult refreshAllDeferred(Shop ozon,Collection<String> skus,BooleanSupplier cancelled,java.util.function.Consumer<RefreshResult> progress)throws IOException {
        return refreshAllDeferred(skus,cancelled,progress,batch->refreshMissing(ozon,batch,cancelled));
    }
    static RefreshResult refreshAllDeferred(Collection<String> skus,BooleanSupplier cancelled,java.util.function.Consumer<RefreshResult> progress,RefreshBatch refresh)throws IOException {
        Objects.requireNonNull(skus);Objects.requireNonNull(cancelled);Objects.requireNonNull(progress);Objects.requireNonNull(refresh);
        if(skus.size()>10000)throw new IllegalArgumentException("KIZ_CROSS_SELECTION_LIMIT");
        var pending=new LinkedHashSet<>(skus);var issues=new LinkedHashMap<String,String>();int attempted=0,verified=0;
        while(!pending.isEmpty()){
            checkCancelled(cancelled);var before=pending.size();var batch=refresh.run(pending);
            attempted+=batch.attempted();verified+=batch.verified();
            var next=new LinkedHashSet<String>();
            batch.issues().forEach((sku,code)->{if("KIZ_CROSS_REFRESH_DEFERRED".equals(code))next.add(sku);else issues.put(sku,code);});
            progress.accept(new RefreshResult(attempted,verified,Map.copyOf(batch.issues())));
            if(batch.issues().containsValue("NETWORK_TIMEOUT")||batch.issues().containsValue("API_COOLDOWN")||next.size()>=before){
                next.forEach(sku->issues.put(sku,"KIZ_CROSS_REFRESH_DEFERRED"));break;
            }
            pending=next;
        }
        return new RefreshResult(attempted,verified,issues);
    }
    static RefreshResult refreshMissing(Shop ozon,Collection<String> skus,BooleanSupplier cancelled,ObservationReader reader)throws IOException {
        return refreshMissing(ozon,skus,cancelled,reader,REFRESH_LINK_LIMIT,REFRESH_BUDGET_NANOS,System::nanoTime);
    }
    static RefreshResult refreshMissing(Shop ozon,Collection<String> skus,BooleanSupplier cancelled,ObservationReader reader,int maxLinks,long budgetNanos,LongSupplier clock)throws IOException {
        Objects.requireNonNull(cancelled);Objects.requireNonNull(reader);
        Objects.requireNonNull(clock);if(maxLinks<=0||budgetNanos<=0||ozon==null||ozon.getMarketplace()!=Marketplace.OZON||skus==null||skus.size()>10000)throw new IllegalArgumentException("KIZ_CROSS_REFRESH_INVALID");
        var requested=new LinkedHashSet<String>();for(String sku:skus)if(sku!=null&&sku.matches("[1-9][0-9]{0,18}"))requested.add(sku);
        if(requested.isEmpty())return new RefreshResult(0,0,Map.of());
        checkCancelled(cancelled);ListingIdentity.require(ozon);var links=new ArrayList<WbOzonLinkRepository.Link>();
        try(var c=Database.getConnection()){
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='wb_ozon_gtin_links'");var r=p.executeQuery()){if(!r.next())return new RefreshResult(0,0,Map.of());}
            links.addAll(readLinks(c,ozon.getId(),requested));
        }catch(SQLException failure){throw new IOException("KIZ_CROSS_REFRESH_STORAGE_FAILED",failure);}
        if(links.isEmpty())return new RefreshResult(0,0,Map.of());
        RegisteredGtinProof.Scope scope;
        try{scope=RegisteredGtinProof.current(ozon);RegisteredGtinProof.requireProduction(scope);}
        catch(RegisteredGtinProof.UnverifiedOwnerException|IllegalArgumentException invalid){
            var issues=new LinkedHashMap<String,String>();links.forEach(link->issues.put(link.ozonSku(),"CATALOG_OWNER_UNVERIFIED"));return new RefreshResult(0,0,issues);
        }
        var counts=new HashMap<String,Integer>();links.forEach(l->counts.merge(l.ozonSku(),1,Integer::sum));
        // Bound database proof work as well as network work for large selections.
        // Untouched links stay eligible on a subsequent action; no goods are changed.
        var issues=new LinkedHashMap<String,String>();var window=new LinkedHashSet<String>();
        var candidates=links.stream().map(WbOzonLinkRepository.Link::ozonSku).distinct().toList();
        int start=(int)Math.floorMod(REFRESH_CURSOR.getAndAdd(maxLinks),candidates.size());
        for(int i=0;i<Math.min(maxLinks,candidates.size());i++)window.add(candidates.get((start+i)%candidates.size()));
        for(var sku:candidates)if(!window.contains(sku))issues.put(sku,"KIZ_CROSS_REFRESH_DEFERRED");
        links.removeIf(link->!window.contains(link.ozonSku()));
        var already=resolve(ozon,window);
        for(var link:links)if(already.containsKey(new Variant(link.ozonProductId(),link.ozonSku()))&&crossOnly(ozon,link))issues.put(link.ozonSku(),"KIZ_CROSS_PURCHASE_REVIEW_REQUIRED");
        links.removeIf(link->already.containsKey(new Variant(link.ozonProductId(),link.ozonSku())));
        if(links.isEmpty())return new RefreshResult(0,0,issues);
        var attempted=new LinkedHashMap<String,WbOzonLinkRepository.Link>();int attemptedCount=0;
        var snapshots=new HashMap<String,ProductSnapshot>();String deferredReason=null;long started=clock.getAsLong();
        for(var link:links){
            checkCancelled(cancelled);String sku=link.ozonSku();
            if(counts.getOrDefault(sku,0)!=1){issues.put(sku,"KIZ_CROSS_AMBIGUOUS");continue;}
            if(deferredReason!=null){issues.put(sku,deferredReason);continue;}
            if(attemptedCount>=maxLinks||clock.getAsLong()-started>=budgetNanos){issues.put(sku,"KIZ_CROSS_REFRESH_DEFERRED");continue;}
            try {
                var wb=new ShopRepository().findById(link.wbShopId());if(wb==null)throw new SecurityException("WB_OZON_SOURCE_CHANGED");
                try(var c=Database.getConnection()){if(scopedSource(c,wb,ozon,scope,link)==null)throw new SecurityException("WB_OZON_LINK_REVIEW_REQUIRED");}
                attemptedCount++;
                var sourceKey=new ProductKey(wb.getId(),Marketplace.WILDBERRIES,link.wbNmId(),link.wbChrtId());
                String cardKey=wb.getId()+"/"+link.wbNmId();var source=snapshots.get(cardKey);
                if(source==null){source=reader.wb(wb,sourceKey);snapshots.put(cardKey,source);}
                checkCancelled(cancelled);source=rekeyWb(source,sourceKey,link.wbOwner());
                if(source==null||!link.wbRevision().equals(RegistrationReviewedValues.productRevision(source)))throw new IllegalStateException("WB_OZON_SOURCE_CHANGED");
                var variant=source.variants().stream().filter(v->link.wbChrtId().equals(v.id())).toList();
                if(variant.size()!=1||!RegisteredGtinProof.contains(variant.getFirst().barcodes(),link.gtin())&&!WbOzonSourceEvidence.matches(wb,sourceKey,link.gtin(),link.goodId()))throw new IllegalStateException("WB_OZON_SOURCE_CHANGED");
                var key=new GtinSyncModels.ProductKey(ozon.getId(),Marketplace.OZON,link.ozonProductId(),link.ozonOfferId());
                var observed=reader.ozon(ozon,key,link.gtin());checkCancelled(cancelled);
                if(observed==null||!key.equals(observed.key())||!RegisteredGtinProof.sameCode(link.gtin(),observed.gtin())||observed.goodId()!=link.goodId()
                        ||!sku.equals(observed.sku())||!link.ozonOwner().equals(observed.owner())||!link.catalogSource().equals(observed.source())
                        ||!link.ozonRevision().equals(observed.revision()))throw new IllegalStateException("WB_OZON_LINK_REVIEW_REQUIRED");
                cacheObservedWb(wb,ozon,scope,link,source);checkCancelled(cancelled);
                attempted.put(sku,link);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("KIZ_CROSS_REFRESH_CANCELLED");}
            catch(InterruptedIOException interrupted){if(interrupted instanceof java.net.SocketTimeoutException){issues.put(sku,"NETWORK_TIMEOUT");deferredReason="KIZ_CROSS_REFRESH_DEFERRED";}else{Thread.currentThread().interrupt();throw interrupted;}}
            catch(Exception failure){
                String code=com.vncode.app.features.sync.SyncFailure.of(failure,0).code();issues.put(sku,code);
                if(code.equals("API_COOLDOWN")||code.contains("RATE_LIMIT"))deferredReason="API_COOLDOWN";
            }
        }
        checkCancelled(cancelled);var current=resolve(ozon,window);int verified=0;
        for(var entry:attempted.entrySet()){
            var link=entry.getValue();String found=current.get(new Variant(link.ozonProductId(),link.ozonSku()));
            if(RegisteredGtinProof.sameCode(found,link.gtin())){verified++;if(crossOnly(ozon,link))issues.put(entry.getKey(),"KIZ_CROSS_PURCHASE_REVIEW_REQUIRED");}
            else issues.put(entry.getKey(),"KIZ_CROSS_REVIEW_REQUIRED");
        }
        return new RefreshResult(attemptedCount,verified,issues);
    }
    private static boolean crossOnly(Shop ozon,WbOzonLinkRepository.Link link){
        var product=new KizPurchaseMappingProof.Product(link.ozonProductId(),link.ozonSku(),link.gtin());
        try{return !RegisteredGtinProof.sameCode(link.gtin(),KizPurchaseMappingProof.resolve(ozon,List.of(product)).get(product));}
        catch(KizAssignmentException invalid){return true;}
    }
    private static void checkCancelled(BooleanSupplier cancelled)throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new InterruptedIOException("KIZ_CROSS_REFRESH_CANCELLED");
    }

    private static String wbObservationKey(int shopId,String owner,String product,String variant){
        return "kiz.cross.wb."+shopId+"."+ListingIdentity.hash(String.join("\n",owner,product,variant));
    }
    private static void cacheObservedWb(Shop wb,Shop ozon,RegisteredGtinProof.Scope scope,WbOzonLinkRepository.Link link,ProductSnapshot source)throws SQLException {
        var key=new ProductKey(wb.getId(),Marketplace.WILDBERRIES,link.wbNmId(),link.wbChrtId());
        source=rekeyWb(source,key,link.wbOwner());if(source==null||!link.wbRevision().equals(RegistrationReviewedValues.productRevision(source)))throw new IllegalStateException("WB_OZON_SOURCE_CHANGED");
        var variants=source.variants().stream().filter(v->link.wbChrtId().equals(v.id())).toList();if(variants.size()!=1)throw new IllegalStateException("WB_OZON_SOURCE_CHANGED");
        var variant=variants.getFirst();var observation=new WbObservation(link.wbNmId(),link.wbChrtId(),link.wbOwner(),link.wbCatalogSource(),link.gtin(),link.goodId(),link.wbRevision(),source.title(),source.vendorArticle(),variant.size(),variant.russianSize(),variant.barcodes(),source.checkedAt());
        String storageKey=wbObservationKey(wb.getId(),link.wbOwner(),link.wbNmId(),link.wbChrtId());
        com.vncode.app.config.SqliteWriteTransaction.run(c->{
            if(scopedSource(c,wb,ozon,scope,link)==null)throw new SecurityException("WB_OZON_LINK_REVIEW_REQUIRED");
            boolean write=true;
            try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){
                p.setString(1,storageKey);try(var r=p.executeQuery()){if(r.next()){
                    WbObservation previous;try{previous=ProductJson.GSON.fromJson(r.getString(1),WbObservation.class);}catch(RuntimeException invalid){throw new IllegalStateException("KIZ_CROSS_SNAPSHOT_TIMESTAMP_INVALID");}
                    if(previous==null||previous.checkedAt()==null)throw new IllegalStateException("KIZ_CROSS_SNAPSHOT_TIMESTAMP_INVALID");
                    if(previous.checkedAt().isAfter(observation.checkedAt()))write=false;
                }}
            }
            if(write)try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                p.setString(1,storageKey);p.setString(2,ProductJson.GSON.toJson(observation));p.executeUpdate();
            }
            if(scopedSource(c,wb,ozon,scope,link)==null)throw new SecurityException("WB_OZON_LINK_REVIEW_REQUIRED");return null;
        });
    }

    /** Persist scoped identity/Catalog proof separately; never replace product snapshots or documents. */
    public static void cacheObservedOzonSnapshot(Shop shop,ProductSnapshot snapshot,GtinSyncModels.RegisteredGtin observed,long goodId) {
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON||snapshot==null||snapshot.key().shopId()!=shop.getId()
                ||snapshot.key().marketplace()!=Marketplace.OZON||!snapshot.revision().startsWith("live:")
                ||!ListingIdentity.fingerprint(shop).equals(snapshot.metadata().get("owner")))throw new SecurityException("KIZ_CROSS_SNAPSHOT_OWNER_CHANGED");
        Instant now=Instant.now();if(snapshot.checkedAt().isBefore(now.minusSeconds(86400))||snapshot.checkedAt().isAfter(now.plusSeconds(300)))throw new IllegalArgumentException("KIZ_CROSS_SNAPSHOT_STALE");
        String owner=ListingIdentity.fingerprint(shop);ListingIdentity.require(shop);
        if(observed==null)throw new IllegalArgumentException("KIZ_CROSS_CATALOG_UNVERIFIED");
        var capturedScope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(capturedScope);
        String id=observed.details().single("@catalog_id"),sku=snapshot.metadata().getOrDefault("sku","");
        var variants=snapshot.variants().stream().filter(v->snapshot.key().variantId().equals(v.id())).toList();
        if(goodId<=0||!id.matches("[0-9]{1,64}")||!new BigInteger(id).equals(BigInteger.valueOf(goodId))
                ||!capturedScope.source().equals(observed.source())||!RegisteredGtinProof.currentObservation(observed,now)
                ||!sku.matches("[1-9][0-9]{0,18}")||variants.size()!=1)throw new IllegalArgumentException("KIZ_CROSS_CATALOG_UNVERIFIED");
        var variant=variants.getFirst();var key=snapshot.key();
        var identity=new OzonObservation(key.cardId(),key.variantId(),sku,owner,capturedScope.source(),observed.gtin(),goodId,
                RegistrationReviewedValues.productRevision(snapshot),snapshot.title(),snapshot.vendorArticle(),variant.color(),variant.size(),variant.barcodes(),snapshot.checkedAt(),observed);
        String storageKey=observationKey(shop.getId(),owner,key.cardId(),key.variantId());
        try{com.vncode.app.config.SqliteWriteTransaction.run(c->{
            if(!owner.equals(ListingIdentity.fingerprint(c,shop.getId(),Marketplace.OZON)))throw new SecurityException("KIZ_CROSS_SNAPSHOT_OWNER_CHANGED");
            {
                String canonical=RegisteredGtinProof.canonicalCode(observed.gtin());var previous=new ArrayList<GtinSyncModels.RegisteredGtin>();
                try(var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace='OZON' AND length(gtin) IN (8,12,13,14) AND gtin NOT GLOB '*[^0-9]*' AND substr('00000000000000'||gtin,-14)=?")){
                    p.setInt(1,shop.getId());p.setString(2,canonical);try(var r=p.executeQuery()){while(r.next()){
                        var raw=JsonParser.parseString(r.getString(1)).getAsJsonObject();
                        if(!raw.has("owner")||!owner.equals(raw.get("owner").getAsString()))throw new SecurityException("KIZ_CROSS_CATALOG_OWNER_CHANGED");
                        previous.add(ProductJson.GSON.fromJson(raw,GtinSyncModels.RegisteredGtin.class));
                    }}
                }
                if(previous.size()>1)throw new IllegalStateException("KIZ_CROSS_CATALOG_AMBIGUOUS");
                RegisteredGtinProof.requireKnownDocumentState(previous,List.of(observed));
                if(!previous.isEmpty()&&!previous.getFirst().source().equals(capturedScope.source()))throw new SecurityException("KIZ_CROSS_CATALOG_OWNER_CHANGED");
            }
            boolean write=true;
            try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){
                p.setString(1,storageKey);
                try(var r=p.executeQuery()){if(r.next()){
                    OzonObservation previous;try{previous=ProductJson.GSON.fromJson(r.getString(1),OzonObservation.class);}catch(RuntimeException invalid){throw new IllegalStateException("KIZ_CROSS_SNAPSHOT_TIMESTAMP_INVALID");}
                    if(previous==null||previous.checkedAt()==null)throw new IllegalStateException("KIZ_CROSS_SNAPSHOT_TIMESTAMP_INVALID");
                    if(previous.checkedAt().isAfter(snapshot.checkedAt()))write=false;
                }}
            }
            if(write)try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                p.setString(1,storageKey);p.setString(2,ProductJson.GSON.toJson(identity));p.executeUpdate();
            }
            if(!owner.equals(ListingIdentity.fingerprint(c,shop.getId(),Marketplace.OZON))||!capturedScope.equals(RegisteredGtinProof.current(shop)))throw new SecurityException("KIZ_CROSS_SNAPSHOT_OWNER_CHANGED");return null;
        });}catch(SQLException failure){throw new IllegalStateException("KIZ_CROSS_SNAPSHOT_STORAGE_FAILED",failure);}
    }

    public static Map<Variant,String> resolve(Shop ozon) {return resolve(ozon,null);}
    /** Do not let an older broad mapping silently re-enable a cross-only link. */
    public static Set<String> unsafeReviewedSkus(Shop ozon,Map<String,String> identities,Map<String,String> directProof) {
        if(ozon==null||ozon.getMarketplace()!=Marketplace.OZON||identities.isEmpty())return Set.of();
        try(var c=Database.getConnection()){
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='wb_ozon_gtin_links'");var r=p.executeQuery()){if(!r.next())return Set.of();}
            String owner=ListingIdentity.fingerprint(c,ozon.getId(),Marketplace.OZON);var unsafe=new HashSet<String>();
            var links=readLinks(c,ozon.getId(),identities.keySet());var skus=links.stream().map(WbOzonLinkRepository.Link::ozonSku).distinct().toList();
            var exactBySku=new HashMap<String,com.vncode.app.integration.ozon.OzonProductDto>();
            for(int start=0;start<skus.size();start+=10000)exactBySku.putAll(new OzonCatalogRepository().findBySkus(c,ozon.getId(),skus.subList(start,Math.min(start+10000,skus.size()))));
            for(var link:links){
                String chosen=identities.get(link.ozonSku()),direct=directProof.get(link.ozonSku());
                if(chosen!=null&&owner.equals(link.ozonOwner())){
                    var exact=exactBySku.get(link.ozonSku());
                    if(exact==null||!link.ozonProductId().equals(exact.productId())||!link.ozonOfferId().equals(exact.offerId())
                            ||!RegisteredGtinProof.sameCode(chosen,link.gtin())||!RegisteredGtinProof.sameCode(chosen,direct))unsafe.add(link.ozonSku());
                }
            }
            return Set.copyOf(unsafe);
        }catch(SQLException failure){throw new IllegalStateException("KIZ_CROSS_MARKETPLACE_READ_FAILED",failure);}
    }
    /** A contradictory reviewed link requires review even if a direct barcode resolves. */
    static Set<Variant> conflictsWithReviewedLinks(Shop ozon,Map<Variant,String> identities) {
        if(ozon==null||ozon.getMarketplace()!=Marketplace.OZON||identities.isEmpty())return Set.of();
        try(var c=Database.getConnection()){
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='wb_ozon_gtin_links'");var r=p.executeQuery()){if(!r.next())return Set.of();}
            String owner=ListingIdentity.fingerprint(c,ozon.getId(),Marketplace.OZON);
            var conflicts=new HashSet<Variant>();
            for(var link:readLinks(c,ozon.getId(),identities.keySet().stream().map(Variant::sku).distinct().toList())){
                var variant=new Variant(link.ozonProductId(),link.ozonSku());var gtin=identities.get(variant);
                if(gtin!=null&&owner.equals(link.ozonOwner())&&!RegisteredGtinProof.sameCode(gtin,link.gtin()))conflicts.add(variant);
            }
            return Set.copyOf(conflicts);
        }catch(SQLException failure){throw new IllegalStateException("KIZ_CROSS_MARKETPLACE_READ_FAILED",failure);}
    }
    static Map<Variant,String> resolve(Shop ozon,Collection<String> selectedSkus) {
        if(ozon==null||ozon.getMarketplace()!=Marketplace.OZON)return Map.of();
        if(selectedSkus!=null&&selectedSkus.size()>10000)throw new IllegalArgumentException("KIZ_CROSS_SELECTION_LIMIT");
        var filter=selectedSkus==null?null:new LinkedHashSet<>(selectedSkus);if(filter!=null&&filter.isEmpty())return Map.of();
        ListingIdentity.require(ozon);
        RegisteredGtinProof.Scope targetScope;
        try{targetScope=RegisteredGtinProof.current(ozon);RegisteredGtinProof.requireProduction(targetScope);}
        catch(RegisteredGtinProof.UnverifiedOwnerException|IllegalArgumentException invalid){return Map.of();}
        var result=new LinkedHashMap<Variant,String>();var ambiguous=new HashSet<Variant>();var ownersBySku=new HashMap<String,Variant>();
        try(var c=Database.getConnection()) {
            long before=dataVersion(c);c.setAutoCommit(false);
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='wb_ozon_gtin_links'");var r=p.executeQuery()){if(!r.next())return Map.of();}
            var links=readLinks(c,ozon.getId(),filter);
            var shops=new HashMap<Integer,Shop>();
            for(var link:links){
                var wb=shops.computeIfAbsent(link.wbShopId(),id->new ShopRepository().findById(id));if(wb==null)continue;
                try{
                    if(!valid(c,wb,ozon,targetScope,link))continue;
                    var key=new Variant(link.ozonProductId(),link.ozonSku());
                    String gtin=GtinNormalizer.normalize(link.gtin()),previous=result.putIfAbsent(key,gtin);
                    if(previous!=null&&!previous.equals(gtin))ambiguous.add(key);
                    var prior=ownersBySku.putIfAbsent(key.sku(),key);if(prior!=null&&!prior.equals(key)){ambiguous.add(prior);ambiguous.add(key);}
                }catch(SecurityException|RegisteredGtinProof.UnverifiedOwnerException|IllegalArgumentException invalid){/* Unverifiable links never authorize a purchase. */}
            }
            ambiguous.forEach(result::remove);
            c.commit();if(before!=dataVersion(c))return Map.of();
            ListingIdentity.require(ozon);if(!targetScope.equals(RegisteredGtinProof.current(ozon))||before!=dataVersion(c))return Map.of();
            return Map.copyOf(result);
        }catch(SQLException failure){throw new IllegalStateException("KIZ_CROSS_MARKETPLACE_READ_FAILED",failure);}
    }

    private static long dataVersion(Connection c)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("PRAGMA data_version")){if(!r.next())throw new SQLException("KIZ_CROSS_VERSION_UNAVAILABLE");return r.getLong(1);}}
    private static List<WbOzonLinkRepository.Link> readLinks(Connection c,int shopId,Collection<String> selected)throws SQLException {
        var result=new ArrayList<WbOzonLinkRepository.Link>();var keys=selected==null?null:new ArrayList<>(new LinkedHashSet<>(selected));
        int count=keys==null?1:(keys.size()+499)/500;
        for(int start=0;start<count;start++){
            var batch=keys==null?List.<String>of():keys.subList(start*500,Math.min(keys.size(),(start+1)*500));
            String sql="SELECT * FROM wb_ozon_gtin_links WHERE ozon_shop_id=?"+(keys==null?"":" AND ozon_sku IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")");
            try(var p=c.prepareStatement(sql)){p.setInt(1,shopId);for(int i=0;i<batch.size();i++)p.setString(i+2,batch.get(i));try(var r=p.executeQuery()){
                while(r.next())try{result.add(link(r));}catch(RuntimeException malformed){/* Ignore corrupt local links. */}
            }}
        }
        return result;
    }

    private static RegisteredGtinProof.Scope scopedSource(Connection c,Shop wb,Shop ozon,RegisteredGtinProof.Scope scope,WbOzonLinkRepository.Link link)throws SQLException {
        if(wb.getMarketplace()!=Marketplace.WILDBERRIES||wb.getId()==ozon.getId()||link.wbShopId()!=wb.getId()||link.ozonShopId()!=ozon.getId()
                ||!ListingIdentity.fingerprint(wb).equals(link.wbOwner())||!ListingIdentity.fingerprint(ozon).equals(link.ozonOwner())
                ||link.goodId()<=0||!RegisteredGtinValidator.isValid(link.gtin())
                ||GtinNormalizer.isTechnicalRange(link.gtin())||!link.wbNmId().matches("[1-9][0-9]{0,18}")
                ||!link.wbChrtId().matches("[1-9][0-9]{0,18}")||!link.ozonProductId().matches("[1-9][0-9]{0,18}")
                ||!link.ozonSku().matches("[1-9][0-9]{0,18}")||link.ozonOfferId().isBlank()||!link.ozonOfferId().equals(link.ozonOfferId().strip())||link.ozonOfferId().length()>512
                ||!link.wbRevision().matches("[a-f0-9]{64}")
                ||!link.ozonRevision().matches("[a-f0-9]{64}")||!link.evidenceDigest().matches("[a-f0-9]{64}")
                ||!Set.of("EXACT_BARCODE","EXACT_ARTICLE","MANUAL_PAIR").contains(link.evidenceKind())
                ||link.approvedAt().isAfter(Instant.now().plusSeconds(300)))return null;
        var sourceScope=RegisteredGtinProof.current(wb);RegisteredGtinProof.requireProduction(sourceScope);
        if(!sourceScope.source().equals(link.wbCatalogSource())||!scope.source().equals(link.catalogSource())
                ||!sourceScope.inn().equals(scope.inn())||!sourceScope.environment().equals(scope.environment())||!unchangedLink(c,link))return null;
        return sourceScope;
    }
    private static boolean valid(Connection c,Shop wb,Shop ozon,RegisteredGtinProof.Scope scope,WbOzonLinkRepository.Link link)throws SQLException {
        var sourceScope=scopedSource(c,wb,ozon,scope,link);if(sourceScope==null)return false;
        var sourceKey=new ProductKey(wb.getId(),Marketplace.WILDBERRIES,link.wbNmId(),link.wbChrtId());
        var targetKey=new ProductKey(ozon.getId(),Marketplace.OZON,link.ozonProductId(),link.ozonOfferId());
        var sourceObservation=wbObservation(c,wb,sourceKey,link.wbOwner());
        List<String> sourceCodes;
        if(sourceObservation!=null){
            if(!link.wbRevision().equals(sourceObservation.revision())||!link.wbCatalogSource().equals(sourceObservation.source())
                    ||link.goodId()!=sourceObservation.goodId()||!RegisteredGtinProof.sameCode(link.gtin(),sourceObservation.gtin())
                    ||!currentWb(c,wb,link,sourceObservation.title(),sourceObservation.article(),sourceObservation.size(),sourceObservation.russianSize(),sourceObservation.barcodes(),sourceObservation.checkedAt()))return false;
            sourceCodes=sourceObservation.barcodes();
        }else{
            var source=snapshot(c,sourceKey,link.wbOwner());if(source==null||!link.wbRevision().equals(RegistrationReviewedValues.productRevision(source)))return false;
            var variants=source.variants().stream().filter(v->link.wbChrtId().equals(v.id())).toList();if(variants.size()!=1)return false;
            var variant=variants.getFirst();if(!currentWb(c,wb,link,source.title(),source.vendorArticle(),variant.size(),variant.russianSize(),variant.barcodes(),source.checkedAt()))return false;
            sourceCodes=variant.barcodes();
        }
        var observation=observation(c,ozon,targetKey,link.ozonOwner());
        if(observation!=null){
            if(!link.ozonRevision().equals(observation.revision())||!link.catalogSource().equals(observation.source())
                    ||link.goodId()!=observation.goodId()||!RegisteredGtinProof.sameCode(link.gtin(),observation.gtin())
                    ||!link.ozonSku().equals(observation.sku())||!currentOzon(c,ozon,link,observation.title(),observation.article(),observation.color(),observation.size(),observation.barcodes(),observation.checkedAt()))return false;
        }else{
            var target=snapshot(c,targetKey,link.ozonOwner());
            if(target==null||!link.ozonRevision().equals(RegistrationReviewedValues.productRevision(new OzonShopBrand().apply(ozon,target)))
                    ||!link.ozonSku().equals(target.metadata().get("sku")))return false;
            var targetVariants=target.variants().stream().filter(v->link.ozonOfferId().equals(v.id())).toList();if(targetVariants.size()!=1)return false;
            var tv=targetVariants.getFirst();if(!currentOzon(c,ozon,link,target.title(),target.vendorArticle(),tv.color(),tv.size(),tv.barcodes(),target.checkedAt()))return false;
        }
        if(!RegisteredGtinProof.contains(sourceCodes,link.gtin())&&!WbOzonSourceEvidence.matches(wb,sourceKey,link.gtin(),link.goodId()))return false;
        if(!catalog(c,ozon,scope,link,observation))return false;
        ListingIdentity.require(wb);ListingIdentity.require(ozon);
        return scope.equals(RegisteredGtinProof.current(ozon))&&sourceScope.equals(RegisteredGtinProof.current(wb))&&unchangedLink(c,link);
    }

    private static WbOzonLinkRepository.Link link(ResultSet r)throws SQLException {
        return new WbOzonLinkRepository.Link(r.getInt("wb_shop_id"),r.getString("wb_nm_id"),r.getString("wb_chrt_id"),r.getString("wb_owner"),r.getInt("ozon_shop_id"),r.getString("ozon_product_id"),r.getString("ozon_offer_id"),r.getString("ozon_sku"),r.getString("ozon_owner"),r.getString("gtin"),r.getLong("good_id"),r.getString("wb_catalog_source"),r.getString("catalog_source"),r.getString("wb_revision"),r.getString("ozon_revision"),r.getString("evidence_kind"),r.getString("evidence_digest"),Instant.parse(r.getString("approved_at")));
    }
    private static boolean unchangedLink(Connection c,WbOzonLinkRepository.Link expected)throws SQLException {
        try(var p=c.prepareStatement("SELECT * FROM wb_ozon_gtin_links WHERE ozon_shop_id=? AND ozon_product_id=? AND ozon_offer_id=?")){
            p.setInt(1,expected.ozonShopId());p.setString(2,expected.ozonProductId());p.setString(3,expected.ozonOfferId());
            try(var r=p.executeQuery()){return r.next()&&expected.equals(link(r));}
        }
    }

    private static ProductSnapshot snapshot(Connection c,ProductKey key,String owner)throws SQLException {
        try(var p=c.prepareStatement("SELECT payload FROM product_snapshots WHERE shop_id=? AND marketplace=? AND card_id=? AND variant_id=?")){
            p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());
            try(var r=p.executeQuery()){if(r.next())return verifiedSnapshot(r.getString(1),key,owner,false);}
        }
        // WB stores one complete card snapshot at a time, even when the card has several sizes.
        // Re-key the same complete snapshot only for a variant it actually contains.
        if(key.marketplace()==Marketplace.WILDBERRIES)try(var p=c.prepareStatement("SELECT payload FROM product_snapshots WHERE shop_id=? AND marketplace='WILDBERRIES' AND card_id=? ORDER BY checked_at DESC LIMIT 1")){
            p.setInt(1,key.shopId());p.setString(2,key.cardId());try(var r=p.executeQuery()){if(r.next())return verifiedSnapshot(r.getString(1),key,owner,true);}
        }
        return null;
    }
    private static String observationKey(int shopId,String owner,String product,String offer){
        return "kiz.cross.observation."+shopId+"."+ListingIdentity.hash(String.join("\n",owner,product,offer));
    }
    private static OzonObservation observation(Connection c,Shop shop,ProductKey key,String owner)throws SQLException {
        try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){
            p.setString(1,observationKey(shop.getId(),owner,key.cardId(),key.variantId()));
            try(var r=p.executeQuery()){if(!r.next())return null;try{
                var value=ProductJson.GSON.fromJson(r.getString(1),OzonObservation.class);Instant now=Instant.now();
                if(value==null||!owner.equals(value.owner())||!key.cardId().equals(value.productId())||!key.variantId().equals(value.offerId())
                        ||value.checkedAt()==null||value.checkedAt().isBefore(now.minusSeconds(86400))||value.checkedAt().isAfter(now.plusSeconds(300)))return null;
                return value;
            }catch(RuntimeException invalid){return null;}}
        }
    }
    private static WbObservation wbObservation(Connection c,Shop shop,ProductKey key,String owner)throws SQLException {
        try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){
            p.setString(1,wbObservationKey(shop.getId(),owner,key.cardId(),key.variantId()));
            try(var r=p.executeQuery()){if(!r.next())return null;try{
                var value=ProductJson.GSON.fromJson(r.getString(1),WbObservation.class);Instant now=Instant.now();
                if(value==null||!owner.equals(value.owner())||!key.cardId().equals(value.productId())||!key.variantId().equals(value.variantId())
                        ||value.checkedAt()==null||value.checkedAt().isBefore(now.minusSeconds(86400))||value.checkedAt().isAfter(now.plusSeconds(300)))return null;
                return value;
            }catch(RuntimeException invalid){return null;}}
        }
    }
    private static ProductSnapshot verifiedSnapshot(String json,ProductKey key,String owner,boolean sameWbCard){
        try{
            var snapshot=ProductJson.GSON.fromJson(json,ProductSnapshot.class);Instant now=Instant.now();
            if(snapshot==null||snapshot.key().shopId()!=key.shopId()||snapshot.key().marketplace()!=key.marketplace()||!snapshot.key().cardId().equals(key.cardId())
                    ||(!sameWbCard&&!key.equals(snapshot.key()))||!snapshot.revision().startsWith("live:")||!owner.equals(snapshot.metadata().get("owner"))
                    ||snapshot.checkedAt().isBefore(now.minusSeconds(86400))||snapshot.checkedAt().isAfter(now.plusSeconds(300)))return null;
            if(key.equals(snapshot.key()))return snapshot;
            if(!sameWbCard||snapshot.variants().stream().filter(v->key.variantId().equals(v.id())).count()!=1)return null;
            return new ProductSnapshot(key,snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),snapshot.checkedAt(),snapshot.metadata());
        }catch(RuntimeException invalid){return null;}
    }
    private static ProductSnapshot rekeyWb(ProductSnapshot snapshot,ProductKey key,String owner){
        return snapshot==null?null:verifiedSnapshot(ProductJson.GSON.toJson(snapshot),key,owner,true);
    }

    private static boolean currentWb(Connection c,Shop shop,WbOzonLinkRepository.Link link,String title,String article,String size,String russianSize,List<String> barcodes,Instant checkedAt)throws SQLException {
        try(var p=c.prepareStatement("SELECT c.title,c.vendor_code,c.updated_at,s.tech_size,s.wb_size FROM wb_product_cards c JOIN wb_product_sizes s ON s.shop_id=c.shop_id AND s.nm_id=c.nm_id WHERE c.shop_id=? AND c.nm_id=? AND s.chrt_id=?")){
            p.setInt(1,shop.getId());p.setString(2,link.wbNmId());p.setString(3,link.wbChrtId());
            try(var r=p.executeQuery()){if(!r.next()||!same(r.getString(1),title)||!same(r.getString(2),article)
                    ||!same(r.getString(4),size)||!same(r.getString(5),russianSize)||changedAfter(r.getString(3),checkedAt))return false;}
        }
        var current=new HashSet<String>();
        try(var p=c.prepareStatement("SELECT sku FROM wb_product_size_skus WHERE shop_id=? AND chrt_id=?")){
            p.setInt(1,shop.getId());p.setString(2,link.wbChrtId());try(var r=p.executeQuery()){while(r.next())current.add(r.getString(1));}
        }
        if(barcodes==null||!current.equals(new HashSet<>(barcodes)))return false;
        return RegisteredGtinProof.contains(List.copyOf(current),link.gtin())||WbOzonSourceEvidence.matches(shop,new ProductKey(shop.getId(),Marketplace.WILDBERRIES,link.wbNmId(),link.wbChrtId()),link.gtin(),link.goodId());
    }
    private static boolean currentOzon(Connection c,Shop shop,WbOzonLinkRepository.Link link,String title,String article,String color,String size,List<String> barcodes,Instant checkedAt)throws SQLException {
        var products=new OzonCatalogRepository().findBySkus(c,shop.getId(),List.of(link.ozonSku()));var product=products.get(link.ozonSku());
        if(product==null||!link.ozonProductId().equals(product.productId())||!link.ozonOfferId().equals(product.offerId())
                ||!same(product.name(),title)||!same(product.article(),article)||!same(product.color(),color)
                ||!same(product.size(),size)||barcodes==null||!new HashSet<>(product.barcodes()).equals(new HashSet<>(barcodes))
                ||changedAfter(product.updatedAt(),checkedAt))return false;
        // A previously approved cross-market link cannot override another owned,
        // published Catalog GTIN already present on the exact Ozon variant.
        // Check all barcode candidates, including the ambiguous case where the
        // ordinary matcher refuses to pick any one of them.
        try(var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace='OZON' AND length(gtin) IN (8,12,13,14) AND gtin NOT GLOB '*[^0-9]*' AND substr('00000000000000'||gtin,-14)=?")){
            for(String code:product.barcodes()){
                if(!RegisteredGtinValidator.isValid(code)||RegisteredGtinProof.sameCode(code,link.gtin()))continue;
                p.setInt(1,shop.getId());p.setString(2,RegisteredGtinProof.canonicalCode(code));
                try(var r=p.executeQuery()){while(r.next()){
                    try{
                        var raw=JsonParser.parseString(r.getString(1)).getAsJsonObject();
                        var card=ProductJson.GSON.fromJson(raw,GtinSyncModels.RegisteredGtin.class);
                        if(raw.has("owner")&&link.ozonOwner().equals(raw.get("owner").getAsString())
                                &&link.catalogSource().equals(card.source())&&card.published()&&card.tradeUnit()
                                &&RegisteredGtinProof.sameCode(code,card.gtin()))return false;
                    }catch(RuntimeException invalid){return false;}
                }}
            }
        }
        return true;
    }
    private static boolean catalog(Connection c,Shop ozon,RegisteredGtinProof.Scope scope,WbOzonLinkRepository.Link link,OzonObservation observation)throws SQLException {
        try(var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=? AND marketplace='OZON' AND length(gtin) IN (8,12,13,14) AND gtin NOT GLOB '*[^0-9]*' AND substr('00000000000000'||gtin,-14)=?")){
            p.setInt(1,ozon.getId());p.setString(2,RegisteredGtinProof.canonicalCode(link.gtin()));
            try(var r=p.executeQuery()){try{
                GtinSyncModels.RegisteredGtin previous=null;
                if(r.next()){
                    var raw=JsonParser.parseString(r.getString(1)).getAsJsonObject();if(!raw.has("owner")||!ListingIdentity.fingerprint(ozon).equals(raw.get("owner").getAsString())||r.next())return false;
                    previous=ProductJson.GSON.fromJson(raw,GtinSyncModels.RegisteredGtin.class);
                    if(!scope.source().equals(previous.source()))return false;
                }
                var fresh=observation==null?null:observation.catalog();
                if(previous!=null&&fresh!=null)RegisteredGtinProof.requireKnownDocumentState(List.of(previous),List.of(fresh));
                var card=previous==null?fresh:fresh==null||!previous.syncedAt().isBefore(fresh.syncedAt())?previous:fresh;
                if(card==null)return false;String id=card.details().single("@catalog_id");
                return id.matches("[0-9]{1,64}")&&new BigInteger(id).equals(BigInteger.valueOf(link.goodId()))
                        &&scope.source().equals(card.source())&&RegisteredGtinProof.sameCode(link.gtin(),card.gtin())
                        &&RegisteredGtinProof.currentObservation(card,Instant.now());
            }catch(RuntimeException invalid){return false;}}
        }
    }
    private static boolean same(String a,String b){return GtinMatchingService.normalize(Objects.toString(a,"")).equals(GtinMatchingService.normalize(Objects.toString(b,"")));}
    private static boolean changedAfter(String raw,Instant checked){if(raw==null||raw.isBlank())return false;try{return Instant.parse(raw).isAfter(checked.plusSeconds(1));}catch(RuntimeException invalid){return true;}}
}
