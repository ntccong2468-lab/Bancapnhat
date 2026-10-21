package com.vncode.app.features.sync;

import com.google.gson.JsonParser;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppPaths;
import java.nio.file.Path;
import java.util.*;

/** Local persisted readback only: observing a pending result never sends another marketplace request. */
public final class GtinRunStatusSnapshot {
    private GtinRunStatusSnapshot() {}
    public record Context(Shop shop,Set<ProductKey> selected,Path directory,String revision) {
        public Context {selected=Set.copyOf(selected);directory=directory.toAbsolutePath().normalize();}
        @Override public String toString(){return "GtinRunStatusSnapshot.Context[redacted]";}
    }
    public record Snapshot(ZnackGtinBackgroundService.Result result,boolean settled) {}
    public static Context capture(Shop owner,Set<ProductKey> selected) {
        var shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());
        ListingIdentity.require(shop);
        for(var key:selected)ListingIdentity.require(shop,key);
        return new Context(shop,selected,AppPaths.appDataDir(),revision(shop));
    }
    public static Snapshot read(Context context,String run) {
        requireCurrent(context);var repo=new SyncRunRepository();repo.assertOwner(context.shop(),run);
        int total=0,with=0,verified=0,pending=0,blocked=0,submitted=0,unknown=0;boolean unsettled=false;var seen=new HashSet<ProductKey>();
        for(int offset=0;;offset+=1000){var rows=repo.items(run,offset,1000);
            for(var row:rows){
                if(row.productId().startsWith("catalog:"))continue;
                var key=new ProductKey(context.shop().getId(),context.shop().getMarketplace(),row.productId(),row.variantId());
                if(!context.selected().isEmpty()&&!context.selected().contains(key))continue;
                seen.add(key);total++;boolean has=row.gtin()!=null&&!row.gtin().isBlank();if(has)with++;else if(ZnackGtinBackgroundService.unknownSource(row.evidence()))unknown++;
                boolean proof=Set.of("SUCCEEDED","UNCHANGED").contains(row.state())||evidence(row.evidence(),"marketplaceVerified");
                if(has&&proof)verified++;
                boolean failed=Set.of("NEEDS_ACTION","FAILED","CANCELLED").contains(row.state());if(failed)blocked++;
                boolean waiting=has&&!failed&&!Set.of("SUCCEEDED","UNCHANGED","AWAITING_CONFIRMATION").contains(row.state());
                if(waiting&&!proof)pending++;
                if(waiting)unsettled=true;
                if(has&&(evidence(row.evidence(),"marketplaceSubmitted")||proof))submitted++;
            }
            if(rows.size()<1000)break;
        }
        for(var key:context.selected())if(!seen.contains(key)){total++;blocked++;unknown++;}
        requireCurrent(context);repo.assertOwner(context.shop(),run);
        var progress=new ZnackGtinBackgroundService.Progress(Math.max(0,with-pending),with,verified,pending,blocked,submitted);
        return new Snapshot(new ZnackGtinBackgroundService.Result(run,progress,new ZnackGtinBackgroundService.Inventory(total,with,total-with-unknown,0,unknown)),!unsettled);
    }
    private static boolean evidence(String json,String field){try{var value=JsonParser.parseString(json).getAsJsonObject();return value.has(field)&&value.get(field).getAsBoolean();}catch(RuntimeException invalid){return false;}}
    private static void requireCurrent(Context context){
        if(!context.directory().equals(AppPaths.appDataDir().toAbsolutePath().normalize()))throw new SecurityException("SYNC_DATA_DIRECTORY_CHANGED");
        ListingIdentity.require(context.shop());
        if(!context.revision().equals(revision(context.shop())))throw new SecurityException("SYNC_REVIEW_OWNER_CHANGED");
    }
    private static String revision(Shop shop){
        var s=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        return ListingIdentity.hash(String.join("\n",ListingIdentity.fingerprint(shop),Objects.toString(s.resolvedTrueApiBaseUrl(),""),Objects.toString(s.participantInn(),""),Objects.toString(s.ownerInn(),""),Objects.toString(s.signerCertificate(),""),Objects.toString(s.certificateMetadataJson(),""),Objects.toString(s.omsId(),""),Objects.toString(s.omsConnection(),"")));
    }
}
