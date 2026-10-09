package com.vncode.app.integration.wb;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Checkpoint shipping parameters before closing a supply; never blindly retries a PATCH. */
public final class WbShippingService {
    private static final String ACTION="SET_SHIPPING_PARAMETERS";
    private static final Set<String> RUNNING=ConcurrentHashMap.newKeySet();
    private final WbApiClient api;
    private final WbActionLogRepository log;
    private final Clock clock;
    public WbShippingService(){this(new WbApiClient(),new WbActionLogRepository(),Clock.systemDefaultZone());}
    public WbShippingService(WbApiClient api,WbActionLogRepository log,Clock clock){this.api=api;this.log=log;this.clock=clock;}
    public void ensureShipping(Shop shop,WbShippingContract.Parameters parameters,boolean confirmed)throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        if(!confirmed)throw new IllegalArgumentException("WB_SHIPPING_CONFIRMATION_REQUIRED");
        String request=parameters.payload(clock).toString(),key=shop.getId()+":"+parameters.supplyId();
        if(!RUNNING.add(key))throw new IOException("WB_SHIPPING_ALREADY_RUNNING");
        try {
            WbSupplyDto before=api.getSupplyDetail(shop.getApiKey(),parameters.supplyId());
            if(before==null||!parameters.supplyId().equals(before.getId()))throw new IOException("WB_SUPPLY_ID_MISMATCH");
            var previous=log.latestCheckpoint(shop.getId(),ACTION,parameters.supplyId());
            if(previous!=null&&("pending".equals(previous.status())||"ambiguous".equals(previous.status()))) {
                WbShippingContract.Parameters original;
                try {
                    var data=com.google.gson.JsonParser.parseString(previous.requestJson()).getAsJsonObject().getAsJsonArray("data");
                    if(data.size()!=1)throw new IllegalArgumentException();
                    var value=data.get(0).getAsJsonObject();
                    original=new WbShippingContract.Parameters(value.get("supplyId").getAsString(),value.get("shippingType").getAsString(),java.time.LocalDate.parse(value.get("shippingDt").getAsString()),value.get("shippingPointId").getAsLong());
                } catch(RuntimeException error){throw new IOException("WB_SHIPPING_RECONCILE_REQUIRED");}
                if(!matches(before,original))throw new IOException("WB_SHIPPING_RECONCILE_REQUIRED");
                record(shop,original,"reconciled",previous.requestJson(),null);
            }
            if(matches(before,parameters)){record(shop,parameters,"reconciled",request,null);return;}
            record(shop,parameters,"pending",request,null);
            try {api.setSupplyShipping(shop.getApiKey(),parameters,clock);}
            catch(IOException error) {
                if(error instanceof WbApiException wb&&wb.getStatusCode()>=400&&wb.getStatusCode()<500&&wb.getStatusCode()!=408) {
                    record(shop,parameters,"rejected",request,"WB_HTTP_"+wb.getStatusCode());throw error;
                }
                if(readbackMatches(shop,parameters)){record(shop,parameters,"reconciled",request,null);return;}
                record(shop,parameters,"ambiguous",request,"WB_SHIPPING_RECONCILE_REQUIRED");
                throw new IOException("WB_SHIPPING_RECONCILE_REQUIRED");
            }
            if(!readbackMatches(shop,parameters)) {
                record(shop,parameters,"ambiguous",request,"WB_SHIPPING_RECONCILE_REQUIRED");
                throw new IOException("WB_SHIPPING_RECONCILE_REQUIRED");
            }
            record(shop,parameters,"success",request,null);
        } finally {RUNNING.remove(key);}
    }
    private boolean readbackMatches(Shop shop,WbShippingContract.Parameters p){try{return matches(api.getSupplyDetail(shop.getApiKey(),p.supplyId()),p);}catch(IOException|RuntimeException error){return false;}}
    private static boolean matches(WbSupplyDto d,WbShippingContract.Parameters p){return d!=null&&p.supplyId().equals(d.getId())&&p.shippingType().equals(d.getShippingType())&&p.shippingDate().toString().equals(d.getShippingDt())&&Long.valueOf(p.shippingPointId()).equals(d.getShippingPointId());}
    private void record(Shop shop,WbShippingContract.Parameters p,String status,String request,String error){log.record(shop.getId(),ACTION,p.supplyId(),List.of(),status,request,null,error);}
}
