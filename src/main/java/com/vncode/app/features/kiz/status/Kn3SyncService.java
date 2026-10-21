package com.vncode.app.features.kiz.status;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/** Read-only, bounded turns on the existing FBS status stream; no KIZ purchase or upload. */
public final class Kn3SyncService {
    private final Kn3Repository repository=new Kn3Repository();
    @FunctionalInterface public interface WbMetadata {Map<Long,KizService.SgtinMetadata> read(Shop shop,List<Long> ids)throws IOException;}
    private final WbMetadata wb;
    public Kn3SyncService(){this(KizService::getSgtinMetadata);}
    public Kn3SyncService(WbMetadata wb){this.wb=wb;}
    public void wb(Shop shop)throws IOException {
        wb(shop,"");
    }
    public void wb(Shop shop,String supply)throws IOException {
        ListingIdentity.require(shop);var ids=new ArrayList<Long>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT o.order_id FROM wb_orders o LEFT JOIN kn3_marks k ON k.shop_id=o.shop_id AND k.marketplace='WILDBERRIES' AND k.order_id=cast(o.order_id AS TEXT) AND k.owner=? WHERE o.shop_id=? AND (?='' OR EXISTS(SELECT 1 FROM wb_supply_orders s WHERE s.shop_id=o.shop_id AND s.order_id=o.order_id AND s.supply_id=?)) AND coalesce(o.supplier_status,'new') NOT IN ('cancel','cancelled') AND coalesce(o.wb_status,'') NOT IN ('sold','canceled','canceled_by_client','declined_by_client') ORDER BY coalesce(k.checked_at,''),o.order_id LIMIT 100")){
            p.setString(1,ListingIdentity.fingerprint(shop));p.setInt(2,shop.getId());p.setString(3,supply);p.setString(4,supply);try(var r=p.executeQuery()){while(r.next())ids.add(r.getLong(1));}
        }catch(SQLException e){throw new IOException("FBS_KN3_STORAGE",e);}
        if(ids.isEmpty())return;Map<Long,KizService.SgtinMetadata> values;
        try{values=wb.read(shop,ids);}catch(IOException unavailable){repository.unavailable(shop,ids.stream().map(Object::toString).toList(),com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code());throw unavailable;}
        ListingIdentity.require(shop);
        try(var database=Database.openBatchSession()){
            for(long id:ids){var metadata=values.get(id);String order=Long.toString(id);var mark=metadata==null||!metadata.available()?new Kn3Mark(order,"","","",Kn3Mark.State.UNAVAILABLE,"WB_METADATA_UNAVAILABLE"):new Kn3Mark(order,"","",metadata.appliedValue(),Kn3Mark.wbState(metadata.decision(),metadata.appliedValue()),metadata.decision());repository.observe(shop,order,List.of(mark));}
        }
    }
    public void ozon(Shop shop,OzonPostingDto posting,OzonApiClient api)throws IOException {
        ListingIdentity.require(shop);
        // Read status for required marks, locally reserved codes, or a previously seen mark.
        boolean required=!posting.requirements().mandatoryMarkProductIds().isEmpty()||!repository.read(shop,posting.postingNumber()).isEmpty();
        if(!required){var job=new OzonExemplarJobRepository().find(shop.getId(),posting.postingNumber());required=job!=null;}
        if(!required)return;var request=new com.google.gson.JsonObject();request.addProperty("posting_number",posting.postingNumber());
        List<Kn3Mark> marks;
        try{marks=new ArrayList<>(OzonKn3Protocol.parse(posting.postingNumber(),api.exemplarStatus(request)));}catch(IOException unavailable){repository.unavailable(shop,List.of(posting.postingNumber()),com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code());throw unavailable;}
        var skus=posting.items().stream().map(OzonPostingItemDto::sku).collect(java.util.stream.Collectors.toSet());
        if(marks.stream().anyMatch(m->!skus.contains(m.productId())))throw new IOException("OZON_KN3_FOREIGN_PRODUCT");
        var quantities=new HashMap<String,Integer>();posting.items().forEach(item->quantities.merge(item.sku(),item.quantity(),Integer::sum));
        var requiredSkus=posting.items().stream().filter(i->posting.requirements().mandatoryMarkProductIds().contains(i.productId())).map(OzonPostingItemDto::sku).collect(java.util.stream.Collectors.toSet());
        for(var entry:quantities.entrySet())if(requiredSkus.contains(entry.getKey())||marks.stream().anyMatch(m->m.productId().equals(entry.getKey()))){
            long observed=marks.stream().filter(m->m.productId().equals(entry.getKey())).count();if(observed>entry.getValue())throw new IOException("OZON_KN3_INVALID_RESPONSE");
            if(observed<entry.getValue())marks.add(new Kn3Mark(posting.postingNumber(),entry.getKey(),"missing","",Kn3Mark.State.PENDING,"OZON_KN3_INCOMPLETE"));
        }
        ListingIdentity.require(shop);repository.observe(shop,posting.postingNumber(),marks);
    }
}
