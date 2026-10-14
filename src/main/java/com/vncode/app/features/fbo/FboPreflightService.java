package com.vncode.app.features.fbo;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.Shop;
import java.util.*;

/** Read-only partition; accepting the ready subset remains a separate seller decision. */
public final class FboPreflightService {
    public enum State { READY, GTIN_UNVERIFIED, KIZ_SHORTAGE }
    public record Row(FboBarcodePrintItem item,String gtin,int available,int toBuy,State state) {}
    public record Result(List<Row> rows) {
        public Result { rows=List.copyOf(rows); }
        public List<FboBarcodePrintItem> ready(){return rows.stream().filter(r->r.state()==State.READY).map(Row::item).toList();}
        public Map<String,Integer> toBuy(){var amounts=new LinkedHashMap<String,Integer>();var available=new HashMap<String,Integer>();for(var r:rows)if(!r.gtin().isBlank()){amounts.merge(r.gtin(),r.item().quantity(),Math::addExact);available.merge(r.gtin(),r.available(),Math::max);}amounts.replaceAll((g,n)->Math.max(0,n-available.getOrDefault(g,0)));amounts.values().removeIf(n->n==0);return Map.copyOf(amounts);}
    }
    public Result inspect(Shop shop,List<FboBarcodePrintItem> items) {
        ListingIdentity.require(shop);
        if(items==null||items.size()>10000)throw new IllegalArgumentException("Invalid FBO batch");
        var safe=items.stream().filter(i->i!=null&&i.product()!=null&&i.quantity()>0).toList();
        var mappings=new FboVerifiedGtinRepository().resolve(shop.getId(),shop.getMarketplace(),safe.stream().map(FboBarcodePrintItem::product).distinct().toList());
        var inventory=new ZnackGtinInventoryService();var remaining=new HashMap<>(inventory.availableCounts(shop.getId(),mappings.values()));var rows=new ArrayList<Row>();
        for(var item:safe) {
            if(!item.product().requiresKiz()){rows.add(new Row(item,"",0,0,State.READY));continue;}
            String gtin=mappings.get(item.product());
            if(gtin==null){rows.add(new Row(item,"",0,0,State.GTIN_UNVERIFIED));continue;}
            int available=remaining.computeIfAbsent(gtin,g->inventory.availableCount(shop.getId(),g));
            int toBuy=Math.max(0,item.quantity()-available);State state=toBuy==0?State.READY:State.KIZ_SHORTAGE;
            rows.add(new Row(new FboBarcodePrintItem(item.product().withVerifiedGtin(gtin),item.quantity()),gtin,available,toBuy,state));
            // Allocate only rows which can actually proceed. No inventory is reserved here.
            if(state==State.READY)remaining.put(gtin,available-item.quantity());
        }
        ListingIdentity.require(shop);return new Result(rows);
    }
}
