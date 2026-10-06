package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ZnackKizOrderService {
    private static final Duration RECONCILIATION_CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Duration RECONCILIATION_WINDOW = Duration.ofMinutes(5);
    private final ZnackApiClient api; private final ZnackAuthService auth; private final ZnackSignatureProvider signer; private final ZnackRepository repository;
    public ZnackKizOrderService(ZnackApiClient api,ZnackAuthService auth,ZnackSignatureProvider signer,ZnackRepository repository){this.api=api;this.auth=auth;this.signer=signer;this.repository=repository;}
    public KizOrder buy(Settings s,String gtin,int quantity)throws Exception{
        ZnackSafety.requireSigned(s,true);
        if(quantity<=0)throw new IllegalArgumentException("Quantity must be positive.");gtin=GtinNormalizer.requireProductionOrderable(gtin);long id=repository.createDraft(gtin,quantity);
        try{
            String cisType=resolveCisType(s,gtin);
            JsonObject product=new JsonObject();product.addProperty("gtin",gtin);product.addProperty("quantity",quantity);
            product.addProperty("serialNumberType","OPERATOR");product.addProperty("templateId",10);product.addProperty("cisType",cisType);
            JsonObject attributes=new JsonObject();attributes.addProperty("releaseMethodType","PRODUCTION");
            JsonObject order=new JsonObject();order.addProperty("productGroup","lp");order.add("attributes",attributes);
            JsonArray products=new JsonArray();products.add(product);order.add("products",products);
            byte[] body=order.toString().getBytes(StandardCharsets.UTF_8);
            String signature=signer.sign(body, ZnackSignatureContext.SUZ_POST_BODY).base64();
            String token=auth.suzToken(s);
            try {
                JsonObject response=api.createOrder(s.resolvedSuzBaseUrl(),token,s.omsId(),body,signature);String external=required(text(response,"orderId","id"),"Znack order response did not contain an order ID.");
                repository.updateOrder(id,external,"CREATED",OrderStatus.SUBMITTED,null);
                try { repository.log("BUY_KIZ",String.valueOf(id),"INFO","Order submitted",200); }
                catch (RuntimeException ignored) {
                    // An audit-log failure must not turn an accepted order into an ambiguous purchase.
                }
                return repository.findOrder(id).orElseThrow();
            } catch (ZnackApiClient.ZnackApiException e) {
                if (e.statusCode() >= 400 && e.statusCode() < 500) throw e;
                throw new ZnackOrderCreationAmbiguousException("Order creation result is ambiguous after a Znack API server error.",e);
            } catch (Exception e) {
                throw new ZnackOrderCreationAmbiguousException("Order creation result is ambiguous; automatic retry is blocked.",e);
            }
        }catch(Exception e){try{repository.updateOrder(id,null,null,OrderStatus.FAILED,e.getMessage());}catch(RuntimeException auditError){e.addSuppressed(auditError);}throw e;}
    }
    public KizOrder refresh(Settings s,long id)throws Exception{
        ZnackSafety.requireSigned(s,true);
        KizOrder order=repository.findOrder(id).orElseThrow();JsonArray response=api.orderStatus(s.resolvedSuzBaseUrl(),auth.suzToken(s),s.omsId(),required(order.externalOrderId(),"Znack order has no external order ID."));
        String remote="PENDING",reason=null;int available=0;boolean rejected=false;
        for(JsonElement e:response){JsonObject o=e.getAsJsonObject();remote=text(o,"bufferStatus","status");available+=integer(o,"availableCodes");reason=text(o,"rejectionReason");rejected|="REJECTED".equalsIgnoreCase(remote);}
        BufferStatus status=new BufferStatus(remote,available,rejected,reason);repository.updateOrder(id,null,remote,status.localStatus(),reason);return repository.findOrder(id).orElseThrow();
    }
    /**
     * Uses SUZ's recovery-only order list after an ambiguous POST. A match is accepted only when
     * GTIN, quantity and creation time identify exactly one remote order.
     */
    public OrderReconciliation reconcile(Settings s,KizOrder local)throws Exception{
        ZnackSafety.requireSigned(s,true);
        JsonObject response=api.orderList(s.resolvedSuzBaseUrl(),auth.suzToken(s),s.omsId());
        JsonArray orderInfos=response.has("orderInfos")&&response.get("orderInfos").isJsonArray()
                ?response.getAsJsonArray("orderInfos"):new JsonArray();
        Instant earliest=local.createdAt().minus(RECONCILIATION_CLOCK_SKEW);
        Instant latest=local.createdAt().plus(RECONCILIATION_WINDOW);
        List<RemoteOrder> matches=new ArrayList<>();
        for(JsonElement element:orderInfos){
            if(!element.isJsonObject())continue;
            JsonObject candidate=element.getAsJsonObject();
            String externalId=text(candidate,"orderId");
            if(externalId.isBlank()||!candidate.has("createdTimestamp")||candidate.get("createdTimestamp").isJsonNull())continue;
            Instant created;
            try{created=Instant.ofEpochMilli(candidate.get("createdTimestamp").getAsLong());}
            catch(RuntimeException invalid){continue;}
            if(created.isBefore(earliest)||created.isAfter(latest))continue;
            JsonArray buffers=candidate.has("buffers")&&candidate.get("buffers").isJsonArray()
                    ?candidate.getAsJsonArray("buffers"):new JsonArray();
            int matchingQuantity=0;
            for(JsonElement bufferElement:buffers){
                if(!bufferElement.isJsonObject())continue;
                JsonObject buffer=bufferElement.getAsJsonObject();
                String candidateGtin=text(buffer,"gtin");
                if(candidateGtin.isBlank())continue;
                if(!local.gtin().equals(GtinNormalizer.normalize(candidateGtin)))continue;
                matchingQuantity+=integer(buffer,"totalCodes");
            }
            if(matchingQuantity==local.quantity()){
                matches.add(new RemoteOrder(externalId,text(candidate,"orderStatus"),created));
            }
        }
        if(matches.isEmpty())return new OrderReconciliation(ReconciliationStatus.NOT_FOUND,null,0);
        if(matches.size()>1)return new OrderReconciliation(ReconciliationStatus.CONFLICT,null,matches.size());
        return new OrderReconciliation(ReconciliationStatus.MATCHED,matches.getFirst(),1);
    }
    public enum ReconciliationStatus{MATCHED,NOT_FOUND,CONFLICT}
    public record OrderReconciliation(ReconciliationStatus status,RemoteOrder order,int candidateCount){}
    public record RemoteOrder(String externalOrderId,String remoteStatus,Instant createdAt){}
    private String text(JsonObject o,String...k){for(String x:k)if(o.has(x)&&!o.get(x).isJsonNull())return o.get(x).getAsString();return "";}
    private int integer(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():0;}
    private String resolveCisType(Settings settings,String gtin)throws Exception{
        Product stored=repository.findProduct(gtin).orElseThrow(
                ()->new IllegalArgumentException("GTIN is not registered for the selected shop."));
        if(stored.cisType()!=null)return lightIndustryCisType(stored.cisType());
        JsonElement response=api.productInfo(settings.resolvedTrueApiBaseUrl(),auth.trueApiToken(settings),gtin);
        JsonArray products=response.isJsonArray()?response.getAsJsonArray():response.getAsJsonObject().getAsJsonArray("results");
        if(products!=null)for(JsonElement element:products){
            if(!element.isJsonObject())continue;
            JsonObject product=element.getAsJsonObject();
            String candidate=text(product,"gtin","productGtin");
            if(candidate.isBlank()||!gtin.equals(GtinNormalizer.normalize(candidate)))continue;
            String cisType=Boolean.TRUE.equals(bool(product,"isKit","is_kit"))?"BUNDLE":"UNIT";
            repository.updateProductCisType(gtin,cisType);
            return cisType;
        }
        throw new IllegalStateException("Znack did not return the marking code type for GTIN "+gtin+".");
    }
    private Boolean bool(JsonObject o,String...keys){for(String key:keys)if(o.has(key)&&!o.get(key).isJsonNull()){JsonElement value=o.get(key);if(value.isJsonPrimitive()){JsonPrimitive primitive=value.getAsJsonPrimitive();if(primitive.isBoolean())return primitive.getAsBoolean();String text=primitive.getAsString();if("true".equalsIgnoreCase(text)||"1".equals(text))return true;if("false".equalsIgnoreCase(text)||"0".equals(text))return false;}}return null;}
    private String lightIndustryCisType(String value){
        String cisType=value==null?"UNIT":value.trim().toUpperCase(java.util.Locale.ROOT);
        if("UNIT".equals(cisType)||"BUNDLE".equals(cisType))return cisType;
        throw new IllegalStateException("Unsupported light-industry marking code type: "+cisType+".");
    }
    private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalStateException(message);return value;}
}
