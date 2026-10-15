package com.vncode.app.features.fbo;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.Shop;
import java.util.*;

/** Read-only partition; accepting the ready subset remains a separate seller decision. */
public final class FboPreflightService {
    public enum State { READY, GTIN_UNVERIFIED, KIZ_SHORTAGE }
    public record Row(FboBarcodePrintItem item,String gtin,int available,int toBuy,State state,int categoryUsed,int exactAvailable) {
        public Row(FboBarcodePrintItem item,String gtin,int available,int toBuy,State state){this(item,gtin,available,toBuy,state,0,available);}
    }
    public record Result(List<Row> rows) {
        public Result { rows=List.copyOf(rows); }
        public List<FboBarcodePrintItem> ready(){return rows.stream().filter(r->r.state()==State.READY).map(Row::item).toList();}
        public Map<String,Integer> toBuy(){var amounts=new LinkedHashMap<String,Integer>();var available=new HashMap<String,Integer>();for(var r:rows)if(!r.gtin().isBlank()){amounts.merge(r.gtin(),r.item().quantity()-r.categoryUsed(),Math::addExact);available.merge(r.gtin(),r.exactAvailable(),Math::max);}amounts.replaceAll((g,n)->Math.max(0,n-available.getOrDefault(g,0)));amounts.values().removeIf(n->n==0);return Map.copyOf(amounts);}
    }
    public Result inspect(Shop shop,List<FboBarcodePrintItem> items) {
        ListingIdentity.require(shop);
        if(items==null||items.size()>10000)throw new IllegalArgumentException("Invalid FBO batch");
        var safe=items.stream().filter(i->i!=null&&i.product()!=null&&i.quantity()>0).toList();
        var mappings=new FboVerifiedGtinRepository().resolve(shop.getId(),shop.getMarketplace(),safe.stream().map(FboBarcodePrintItem::product).distinct().toList());
        var inventory=new ZnackGtinInventoryService();var remaining=new HashMap<>(inventory.availableCounts(shop.getId(),mappings.values()));var rows=new ArrayList<Row>();
        var coverage=new HashMap<String,Integer>();
        try(var c=com.vncode.app.config.Database.getConnection()){
            c.setAutoCommit(false);var demands=new ArrayList<com.vncode.app.features.kiz.KizCategoryAllocator.Demand>();
            for(int i=0;i<safe.size();i++)if(safe.get(i).product().requiresKiz())demands.add(new com.vncode.app.features.kiz.KizCategoryAllocator.Demand(Integer.toString(i),safe.get(i).product().productId(),safe.get(i).quantity()));
            for(var selection:com.vncode.app.features.kiz.KizCategoryAllocator.select(c,shop.getId(),shop.getMarketplace(),ListingIdentity.fingerprint(shop),demands))coverage.merge(selection.key(),1,Integer::sum);
            c.commit();
        }catch(java.sql.SQLException failure){throw new IllegalStateException("KIZ_CATEGORY_STORAGE",failure);}
        for(int index=0;index<safe.size();index++) {var item=safe.get(index);int categoryUsed=coverage.getOrDefault(Integer.toString(index),0);
            if(!item.product().requiresKiz()){rows.add(new Row(item,"",0,0,State.READY));continue;}
            String gtin=mappings.get(item.product());
            if(gtin==null){rows.add(new Row(item,"",categoryUsed,0,categoryUsed==item.quantity()?State.READY:State.GTIN_UNVERIFIED,categoryUsed,0));continue;}
            int available=remaining.computeIfAbsent(gtin,g->inventory.availableCount(shop.getId(),g));
            int toBuy=Math.max(0,item.quantity()-categoryUsed-available);State state=toBuy==0?State.READY:State.KIZ_SHORTAGE;
            rows.add(new Row(new FboBarcodePrintItem(item.product().withVerifiedGtin(gtin),item.quantity()),gtin,available+categoryUsed,toBuy,state,categoryUsed,available));
            // Allocate only rows which can actually proceed. No inventory is reserved here.
            if(state==State.READY)remaining.put(gtin,available-(item.quantity()-categoryUsed));
        }
        ListingIdentity.require(shop);return new Result(rows);
    }
}
