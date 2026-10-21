package com.vncode.app.integration.wb;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;

/** Reads this seller's successful shipping history; never invents a point or a seller country. */
public final class WbShippingHistoryService {
    private final WbApiClient api;
    private final WbShippingPreferenceService preferences;
    public WbShippingHistoryService(){this(new WbApiClient(),new WbShippingPreferenceService());}
    public WbShippingHistoryService(WbApiClient api,WbShippingPreferenceService preferences){this.api=api;this.preferences=preferences;}
    public List<WbShippingPreferenceService.Location> load(Shop shop,String supplyId)throws IOException {
        MarketplaceGuard.requireWildberries(shop);ListingIdentity.require(shop);
        var saved=preferences.history(shop);
        if(!saved.isEmpty())return saved;
        var current=api.getSupplyDetail(shop.getApiKey(),supplyId);
        if(current==null||!supplyId.equals(current.getId()))throw new IOException("WB_SUPPLY_ID_MISMATCH");
        var locations=new LinkedHashMap<Long,WbShippingPreferenceService.Location>();
        add(locations,current);
        var historical=new ArrayList<WbSupplyDto>();var cursors=new HashSet<Long>();long next=0;
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(25).toNanos();
        do {
            if(!cursors.add(next))throw new IOException("WB_SHIPPING_HISTORY_CURSOR_REPEATED");
            ListingIdentity.require(shop);
            WbSuppliesResponse page;
            try {page=api.getSupplies(shop.getApiKey(),next,1000);}
            catch(IOException error){if(locations.isEmpty())throw error;break;}
            if(page==null||page.getSupplies()==null)throw new IOException("WB_SHIPPING_HISTORY_INVALID");
            for(var row:page.getSupplies())if(row!=null&&Boolean.TRUE.equals(row.getDone())&&row.getId()!=null&&!row.getId().equals(supplyId))historical.add(row);
            if(page.getSupplies().isEmpty()||page.getNext()==null||page.getNext()==0)break;
            next=page.getNext();
            if(historical.size()>=1000||System.nanoTime()>=deadline)break;
        }while(true);
        historical.sort(Comparator.comparing(WbShippingHistoryService::date).reversed());
        for(var row:historical)add(locations,row);
        // Some versions of the list endpoint omit shipping fields. Read recent completed details only.
        if(locations.isEmpty())for(var row:historical.stream().limit(20).toList()) {
            if(System.nanoTime()>=deadline)break;
            ListingIdentity.require(shop);
            var detail=api.getSupplyDetail(shop.getApiKey(),row.getId());
            if(detail==null||!row.getId().equals(detail.getId()))throw new IOException("WB_SUPPLY_ID_MISMATCH");
            add(locations,detail);if(!locations.isEmpty())break;
        }
        var legacy=preferences.load(shop.getId());
        // Legacy city is a lookup hint only. Only IDs found on the current account are eligible.
        if(!locations.isEmpty()&&System.nanoTime()<deadline&&legacy.city()!=null&&!legacy.city().isBlank()&&current.getCargoType()!=null) {
            try {
                var points=api.getShippingPoints(shop.getApiKey(),legacy.city(),current.getCargoType());
                for(var point:points){var previous=locations.get(point.id());if(previous!=null)locations.put(point.id(),new WbShippingPreferenceService.Location(point.id(),previous.shippingType(),legacy.city(),point.toString()));}
            }catch(IOException|IllegalArgumentException unavailable){/* Display-address lookup cannot invalidate historical point IDs. */}
        }
        ListingIdentity.require(shop);return locations.values().stream().limit(100).toList();
    }
    private static String date(WbSupplyDto row){return Objects.toString(row.getClosedAt(),Objects.toString(row.getCreatedAt(),""));}
    private static void add(Map<Long,WbShippingPreferenceService.Location> locations,WbSupplyDto row) {
        if(row.getShippingPointId()==null)return;
        var location=new WbShippingPreferenceService.Location(row.getShippingPointId(),row.getShippingType(),null,null);
        if(location.valid())locations.putIfAbsent(location.pointId(),location);
    }
}
