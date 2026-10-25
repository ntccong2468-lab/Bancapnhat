package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Read-only paired source receipt. Neither cached rows nor a partial page can unlock registration. */
public final class RegistrationSources {
    public static final String KIND="registration_sources";
    public record Readiness(boolean ready,String run,String step,String reason,String completed) {
        public boolean hasSnapshot(){return !run.isBlank()&&!completed.isBlank();}
    }
    public record Member(String product,String variant,String sku,String article,String color,String size) {}
    @FunctionalInterface interface MarketReader {void read(Shop shop,Consumer<List<Member>> page)throws Exception;}
    @FunctionalInterface interface CatalogReader {ZnackCatalogSyncService.Result read(Shop shop)throws Exception;}
    private static final Set<String> ACTIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final MarketReader market;
    private final CatalogReader catalog;
    private final SyncRunRepository reports=new SyncRunRepository();
    public RegistrationSources(){this(RegistrationSources::readMarketplace,shop->new ZnackCatalogSyncService().refresh(shop,true,p->{}));}
    public RegistrationSources(Consumer<NationalCatalogGtinSource.Progress> progress){this(RegistrationSources::readMarketplace,shop->new ZnackCatalogSyncService().refresh(shop,true,progress));}
    RegistrationSources(MarketReader market,CatalogReader catalog){this.market=market;this.catalog=catalog;}

    public Readiness check(Shop shop) {
        try {
            ListingIdentity.require(shop);var verified=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(verified);String scope=verified.source();
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id,state,step,error_code,source_revision,finished_at FROM sync_runs WHERE shop_id=? AND owner=? AND kind=? ORDER BY created_at DESC,rowid DESC LIMIT 1")) {
                p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,KIND);
                try(var row=p.executeQuery()) {
                    if(!row.next())return blocked("","","SOURCE_SYNC_REQUIRED");
                    String run=row.getString(1),step=row.getString(3);
                    if(!scope.equals(row.getString(5)))return blocked(run,step,"SOURCE_CONFIGURATION_CHANGED");
                    if(!"SUCCEEDED".equals(row.getString(2)))return blocked(run,step,row.getString(4).isBlank()?"SOURCE_SYNC_"+row.getString(2):row.getString(4));
                    Instant finished=Instant.parse(row.getString(6)),now=Instant.now();
                    if(finished.isBefore(now.minus(Duration.ofHours(24)))||finished.isAfter(now.plusSeconds(300)))return blocked(run,step,"SOURCE_SYNC_EXPIRED");
                    return new Readiness(true,run,step,"",finished.toString());
                }
            }
        }catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
        catch(RuntimeException e){return blocked("","",SyncFailure.of(e,0).code());}
    }
    public Readiness require(Shop shop){var ready=check(shop);if(!ready.ready())throw new IllegalStateException(ready.reason());return ready;}
    /** Durable list membership survives restarts, upgrades and failed refreshes.
     * A historical receipt is display data only; require() still gates every new action. */
    public Readiness display(Shop shop){
        var current=check(shop);if(current.ready())return current;
        try{
            ListingIdentity.require(shop);var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id,step,finished_at FROM sync_runs WHERE shop_id=? AND owner=? AND kind=? AND state='SUCCEEDED' AND source_revision=? ORDER BY created_at DESC,rowid DESC LIMIT 1")){
                p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,KIND);p.setString(4,scope.source());
                try(var row=p.executeQuery()){
                    if(row.next()){
                        Instant completed=Instant.parse(row.getString(3));
                        if(!completed.isAfter(Instant.now().plusSeconds(300)))return new Readiness(false,row.getString(1),row.getString(2),current.reason(),completed.toString());
                    }
                }
            }
        }catch(SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
        catch(RuntimeException unavailable){return current;}
        return current;
    }
    public Readiness requireDisplay(Shop shop){var saved=display(shop);if(!saved.hasSnapshot())throw new IllegalStateException(saved.reason());return saved;}
    public void requireMember(Shop shop,String product,String variant){
        String run=require(shop).run();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM sync_run_items WHERE run_id=? AND product_id=? AND variant_id=?")){
            p.setString(1,run);p.setString(2,product);p.setString(3,variant);try(var row=p.executeQuery()){if(!row.next())throw new IllegalArgumentException("SOURCE_PRODUCT_NOT_OBSERVED");}
        }catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
    }
    public void requireMembers(Shop shop,Collection<SyncRunRepository.Item> selected){
        var receipt=require(shop);var members=new HashSet<String>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT product_id,variant_id FROM sync_run_items WHERE run_id=?")){
            p.setString(1,receipt.run());try(var r=p.executeQuery()){while(r.next())members.add(r.getString(1)+"\u001f"+r.getString(2));}
        }catch(SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
        for(var item:selected)if(!members.contains(item.productId()+"\u001f"+item.variantId()))throw new IllegalArgumentException("SOURCE_PRODUCT_NOT_OBSERVED");
        if(!receipt.equals(require(shop)))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
    }
    private static Readiness blocked(String run,String step,String reason){return new Readiness(false,run,step,reason,"");}

    public Readiness refresh(Shop shop,Consumer<String> progress)throws Exception {
        String active=System.getProperty("vncode.appdata.dir","")+"/"+shop.getId();
        if(!ACTIVE.add(active))throw new IllegalStateException("SOURCE_SYNC_RUNNING");
        try{return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            var verified=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(verified);String scope=verified.source();
            String run=reports.begin(shop,KIND,"READING_MARKETPLACE");
            try {
                try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_runs SET source_revision=? WHERE id=?")){p.setString(1,scope);p.setString(2,run);p.executeUpdate();}
                progress.accept("READING_MARKETPLACE");
                var seen=new HashSet<String>();
                market.read(shop,page->{
                    assertScope(shop,scope);
                    var rows=new ArrayList<SyncRunRepository.Item>();
                    for(var member:page){
                        if(member.product().isBlank()||member.variant().isBlank()||!seen.add(member.product()+"\u001f"+member.variant()))throw new IllegalStateException("SOURCE_DUPLICATE_PRODUCT");
                        if(seen.size()>100000)throw new IllegalStateException("SOURCE_PRODUCT_LIMIT");
                        rows.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),run,member.product(),member.variant(),member.sku(),member.article(),member.color(),member.size(),"","SUCCEEDED","READING_MARKETPLACE","","","{}"));
                    }
                    reports.addBatch(shop,run,rows);
                });
                assertScope(shop,scope);reports.stage(shop,run,"RUNNING","READING_ZNACK","");progress.accept("READING_ZNACK");
                var result=catalog.read(shop);assertScope(shop,scope);
                if(!result.issues().isEmpty()) {
                    reports.stage(shop,run,"PARTIAL","READING_ZNACK","SOURCE_CATALOG_PARTIAL");return check(shop);
                }
                reports.stage(shop,run,"SUCCEEDED","SOURCES_COMPLETE","");progress.accept("SOURCES_COMPLETE");return check(shop);
            }catch(Exception error){
                try{reports.stage(shop,run,"FAILED",reports.step(run),SyncFailure.of(error,0).code());}catch(RuntimeException changed){error.addSuppressed(changed);}
                throw error;
            }
        });}finally{ACTIVE.remove(active);}
    }
    private static void assertScope(Shop shop,String scope){if(Thread.currentThread().isInterrupted())throw new IllegalStateException("SOURCE_SYNC_CANCELLED");if(!scope.equals(RegisteredGtinProof.current(shop).source()))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");}
    private static void readMarketplace(Shop shop,Consumer<List<Member>> sink)throws Exception {
        if(shop.getMarketplace()==Marketplace.WILDBERRIES) {
            new WbProductSyncService().syncFromStart(shop,cards->{
                var members=new ArrayList<Member>();
                for(var card:cards){
                    if(card.getNmID()==null||card.getNmID()<=0||card.getSizes()==null)throw new IllegalStateException("WB_INCOMPLETE_PRODUCT_PAGE");
                    for(var size:card.getSizes()){
                        if(size.getChrtID()==null||size.getChrtID()<=0)throw new IllegalStateException("WB_INCOMPLETE_VARIANT");
                        members.add(new Member(card.getNmID().toString(),size.getChrtID().toString(),"",safe(card.getVendorCode()),"",safe(size.getTechSize())));
                    }
                }sink.accept(members);
            });
        }else if(shop.getMarketplace()==Marketplace.OZON) {
            new OzonCatalogSyncService(shop.getId(),new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey())))
                .syncForRegistration(rows->sink.accept(rows.stream().map(p->new Member(p.productId(),p.offerId(),p.sku(),p.article(),p.color(),p.size())).toList()));
        }else throw new IllegalArgumentException("SOURCE_MARKETPLACE_UNSUPPORTED");
    }
    private static String safe(String value){return value==null?"":value;}
}
