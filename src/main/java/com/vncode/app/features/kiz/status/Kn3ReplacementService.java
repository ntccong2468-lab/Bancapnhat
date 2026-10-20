package com.vncode.app.features.kiz.status;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.print.*;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import com.vncode.app.models.Kiz;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Explicit replacement of an exact, freshly rejected code. Never buys KIZ or ships an order. */
public final class Kn3ReplacementService {
    @FunctionalInterface public interface WbQueue {void enqueue(Shop shop,List<OrderExportWorkflow.KizAttachmentAssignment> rows);}
    private final Kn3SyncService.WbMetadata wb;
    private final WbQueue queue;
    private final Function<Shop,OzonApiClient> ozon;
    private final Kn3Repository repository=new Kn3Repository();
    public Kn3ReplacementService(){this(KizService::getSgtinMetadata,(shop,rows)->KizAttachmentCoordinator.getInstance().enqueue(shop,"KN3:"+rows.getFirst().orderId(),"KN3",rows),s->new OzonApiClient(s.getId(),new OzonCredentials(s.getClientId(),s.getApiKey())));}
    public Kn3ReplacementService(Kn3SyncService.WbMetadata wb,WbQueue queue,Function<Shop,OzonApiClient> ozon){this.wb=wb;this.queue=queue;this.ozon=ozon;}
    public void replace(Shop shop,Kn3Mark target,String value)throws IOException {
        replace(shop,target,value,null);
    }
    public void replace(Shop shop,Kn3Mark target,String value,Kiz source)throws IOException {
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.KIZ);LicenseRuntime.require(Permission.SELLER_API);
        String code=Objects.toString(KizService.scannerSafeCode(Objects.toString(value,"").strip()),"");
        if(target.state()!=Kn3Mark.State.REJECTED||code.isBlank()||code.length()>8192||target.fingerprint().equals(Kn3Mark.fingerprint(code)))throw new IOException("FBS_KN3_NEW_CODE_REQUIRED");
        ShopOperationCoordinator.withActiveShop(shop.getId(),()->{requireLocalOrder(shop,target.orderId());
            if(shop.getMarketplace()==Marketplace.WILDBERRIES)replaceWb(shop,target,code,source);else replaceOzon(shop,target,code,source);return null;
        });
    }
    private void replaceWb(Shop shop,Kn3Mark target,String code,Kiz source)throws IOException {
        long order;try{order=Long.parseLong(target.orderId());}catch(NumberFormatException e){throw new IOException("WB_KN3_INVALID_ORDER");}
        var metadata=wb.read(shop,List.of(order)).get(order);ListingIdentity.require(shop);
        if(metadata==null||!metadata.available()||Kn3Mark.wbState(metadata.decision(),metadata.appliedValue())!=Kn3Mark.State.REJECTED||!target.fingerprint().equals(Kn3Mark.fingerprint(metadata.appliedValue())))throw new IOException("WB_KN3_CHANGED");
        String id=repository.begin(shop,target,code,"{}",source);
        try{queue.enqueue(shop,List.of(new OrderExportWorkflow.KizAttachmentAssignment(order,code,null,true,target.fingerprint())));}
        catch(RuntimeException failure){repository.dispatch(shop,id,"FAILED");throw failure;}
    }
    private void replaceOzon(Shop shop,Kn3Mark target,String code,Kiz source)throws IOException {
        LicenseRuntime.require(Permission.PACKING);var api=ozon.apply(shop);
        var raw=api.getPosting(target.orderId(),true);var posting=OzonJson.requirePostingDetail(raw,target.orderId());
        if(!Set.of("awaiting_packaging","awaiting_deliver").contains(posting.status()))throw new IOException("OZON_KN3_EDIT_UNAVAILABLE");
        var request=new com.google.gson.JsonObject();request.addProperty("posting_number",target.orderId());var status=api.exemplarStatus(request);
        var marks=OzonKn3Protocol.parse(target.orderId(),status);var skus=posting.items().stream().map(OzonPostingItemDto::sku).collect(java.util.stream.Collectors.toSet());
        if(marks.stream().anyMatch(m->!skus.contains(m.productId())))throw new IOException("OZON_KN3_FOREIGN_PRODUCT");
        var payload=OzonKn3Protocol.replacement(target.orderId(),status,target,code);var detail=OzonKn3Protocol.result(raw);if(detail.has("multi_box_qty"))payload.add("multi_box_qty",detail.get("multi_box_qty").deepCopy());
        var quantities=new HashMap<String,Integer>();posting.items().forEach(item->quantities.merge(item.sku(),item.quantity(),Integer::sum));
        for(var item:payload.getAsJsonArray("products")){var product=item.getAsJsonObject();String sku=product.get("product_id").getAsString();if(!quantities.containsKey(sku)||product.getAsJsonArray("exemplars").size()!=quantities.get(sku))throw new IOException("OZON_KN3_INCOMPLETE");}
        ListingIdentity.require(shop);String id=repository.begin(shop,target,code,payload.toString(),source);
        try{repository.dispatch(shop,id,"SENDING");LicenseRuntime.require(Permission.KIZ);LicenseRuntime.require(Permission.PACKING);ListingIdentity.require(shop);api.setExemplars(payload);repository.dispatch(shop,id,"AWAITING");}
        catch(OzonApiException failure){repository.dispatch(shop,id,failure.ambiguousMutation()?"RECONCILE":"FAILED");throw failure;}
        catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException|SecurityException failure){repository.dispatch(shop,id,"FAILED");throw failure;}
        catch(IOException|RuntimeException failure){repository.dispatch(shop,id,"RECONCILE");throw failure;}
    }
    private static void requireLocalOrder(Shop shop,String order)throws IOException {
        String sql=shop.getMarketplace()==Marketplace.WILDBERRIES?"SELECT 1 FROM wb_orders WHERE shop_id=? AND order_id=?":"SELECT 1 FROM ozon_postings WHERE shop_id=? AND posting_number=?";
        try(var c=Database.getConnection();var p=c.prepareStatement(sql)){p.setInt(1,shop.getId());p.setString(2,order);try(var r=p.executeQuery()){if(!r.next())throw new IOException("FBS_KN3_FOREIGN_ORDER");}}
        catch(java.sql.SQLException e){throw new IOException("FBS_KN3_STORAGE",e);}
    }
}
