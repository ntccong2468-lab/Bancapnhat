package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ZnackKizOrderService {
    public static final String BUFFER_MULTIPLE = "SUZ_KIZ_BUFFER_MULTIPLE";
    public static final String BUFFER_MISSING = "SUZ_KIZ_BUFFER_MISSING";
    public static final String BUFFER_STATUS_MISSING = "SUZ_KIZ_BUFFER_STATUS_MISSING";
    private static final Duration RECONCILIATION_CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Duration RECONCILIATION_WINDOW = Duration.ofMinutes(5);
    private final ZnackApiClient api; private final ZnackAuthService auth; private final ZnackSignatureProvider signer; private final ZnackRepository repository;
    public ZnackKizOrderService(ZnackApiClient api,ZnackAuthService auth,ZnackSignatureProvider signer,ZnackRepository repository){this.api=api;this.auth=auth;this.signer=signer;this.repository=repository;}
    public KizOrder buy(Settings s,String gtin,int quantity)throws Exception{
        ZnackSafety.requireSigned(s,true);
        if(quantity<=0)throw new IllegalArgumentException("Quantity must be positive.");gtin=GtinNormalizer.requireProductionOrderable(gtin);
        LicenseRuntime.require(Permission.KIZ);
        long id=repository.createDraft(gtin,quantity);
        try{
            String cisType=resolveCisType(s,gtin);
            JsonObject product=new JsonObject();product.addProperty("gtin",gtin);product.addProperty("quantity",quantity);
            product.addProperty("serialNumberType","OPERATOR");product.addProperty("templateId",10);product.addProperty("cisType",cisType);
            JsonObject attributes=new JsonObject();attributes.addProperty("releaseMethodType","PRODUCTION");
            JsonObject order=new JsonObject();order.addProperty("productGroup","lp");order.add("attributes",attributes);
            JsonArray products=new JsonArray();products.add(product);order.add("products",products);
            byte[] body=order.toString().getBytes(StandardCharsets.UTF_8);
            LicenseRuntime.require(Permission.KIZ);
            String signature=signer.sign(body, ZnackSignatureContext.SUZ_POST_BODY).base64();
            String token=auth.suzToken(s);
            // A denied lease here proves no POST occurred; keep it outside the ambiguous-send catch.
            LicenseRuntime.require(Permission.KIZ);
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
        JsonObject buffer=null;
        for(JsonElement element:response){
            if(!element.isJsonObject())continue;
            JsonObject candidate=element.getAsJsonObject();String candidateGtin=text(candidate,"gtin");
            if(candidateGtin.isBlank())continue;
            try{if(!order.gtin().equals(GtinNormalizer.normalize(candidateGtin)))continue;}
            catch(IllegalArgumentException invalid){continue;}
            if(buffer!=null)throw new IOException(BUFFER_MULTIPLE);
            buffer=candidate;
        }
        if(buffer==null)throw new IOException(BUFFER_MISSING);
        String remote=text(buffer,"bufferStatus","status"),reason=text(buffer,"rejectionReason");
        if(remote.isBlank())throw new IOException(BUFFER_STATUS_MISSING);
        BufferStatus status=new BufferStatus(remote,integer(buffer,"availableCodes"),"REJECTED".equalsIgnoreCase(remote),reason);
        repository.updateOrder(id,null,remote,status.localStatus(),reason);return repository.findOrder(id).orElseThrow();
    }
    /**
     * Uses SUZ's recovery-only order list after an ambiguous POST. A match is accepted only when
     * GTIN, quantity and creation time identify exactly one remote order.
     */
    public OrderReconciliation reconcile(Settings s,KizOrder local)throws Exception{
        ZnackSafety.requireSigned(s,true);
        JsonObject response=api.orderList(s.resolvedSuzBaseUrl(),auth.suzToken(s),s.omsId());
        if(response==null||hasReconciliationErrors(response)||!response.has("orderInfos")||!response.get("orderInfos").isJsonArray())throw invalidReconciliation(null);
        // The published list request is scoped by omsId. Do not invent a mandatory
        // response field; if the server supplies one, it must be the original OMS.
        if(response.has("omsId")&&!strictText(response,"omsId").equals(s.omsId()))throw invalidReconciliation(null);
        JsonArray orderInfos=response.getAsJsonArray("orderInfos");
        Instant earliest=local.createdAt().minus(RECONCILIATION_CLOCK_SKEW);
        Instant latest=local.createdAt().plus(RECONCILIATION_WINDOW);
        List<RemoteOrder> matches=new ArrayList<>();var identities=new java.util.HashSet<String>();
        for(JsonElement element:orderInfos){
            if(!element.isJsonObject())throw invalidReconciliation(null);
            JsonObject candidate=element.getAsJsonObject();if(hasReconciliationErrors(candidate))throw invalidReconciliation(null);
            String externalId=strictText(candidate,"orderId"),status=strictText(candidate,"orderStatus");
            if(!identities.add(externalId))throw invalidReconciliation(null);
            Instant created;
            try{created=Instant.ofEpochMilli(exactNonnegative(candidate,"createdTimestamp"));}
            catch(RuntimeException invalid){throw invalidReconciliation(invalid);}
            if(!candidate.has("buffers")||!candidate.get("buffers").isJsonArray()||candidate.getAsJsonArray("buffers").isEmpty())throw invalidReconciliation(null);
            int matchingQuantity=0;
            for(JsonElement bufferElement:candidate.getAsJsonArray("buffers")){
                if(!bufferElement.isJsonObject())throw invalidReconciliation(null);
                JsonObject buffer=bufferElement.getAsJsonObject();if(hasReconciliationErrors(buffer))throw invalidReconciliation(null);
                String candidateGtin=strictText(buffer,"gtin");if(!candidateGtin.matches("[0-9]{8,14}"))throw invalidReconciliation(null);
                int quantity;
                try{quantity=Math.toIntExact(exactNonnegative(buffer,"totalCodes"));}
                catch(RuntimeException invalid){throw invalidReconciliation(invalid);}
                if(local.gtin().equals(GtinNormalizer.normalize(candidateGtin)))try{matchingQuantity=Math.addExact(matchingQuantity,quantity);}
                catch(ArithmeticException invalid){throw invalidReconciliation(invalid);}
            }
            if(!created.isBefore(earliest)&&!created.isAfter(latest)&&matchingQuantity==local.quantity())matches.add(new RemoteOrder(externalId,status,created));
        }
        if(matches.isEmpty())return new OrderReconciliation(ReconciliationStatus.NOT_FOUND,null,0);
        if(matches.size()>1)return new OrderReconciliation(ReconciliationStatus.CONFLICT,null,matches.size());
        return new OrderReconciliation(ReconciliationStatus.MATCHED,matches.getFirst(),1);
    }
    private static IOException invalidReconciliation(Throwable cause){return new IOException("SUZ_ORDER_RECONCILIATION_INVALID",cause);}
    private static String strictText(JsonObject value,String field)throws IOException{
        var raw=value.get(field);if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString()||raw.getAsString().isBlank())throw invalidReconciliation(null);return raw.getAsString();
    }
    private static long exactNonnegative(JsonObject value,String field){
        var raw=value.get(field);if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Missing numeric field");
        long result=raw.getAsBigDecimal().longValueExact();if(result<0)throw new IllegalArgumentException("Negative numeric field");return result;
    }
    private static boolean hasReconciliationErrors(JsonObject value){
        for(String key:List.of("error","errorCode","errorMessage","error_message","errors","globalErrors","fieldErrors")){
            var raw=value.get(key);if(raw==null||raw.isJsonNull())continue;
            if(raw.isJsonArray()&&raw.getAsJsonArray().isEmpty()||raw.isJsonObject()&&raw.getAsJsonObject().size()==0)continue;
            if(raw.isJsonPrimitive()){var primitive=raw.getAsJsonPrimitive();if(primitive.isString()&&primitive.getAsString().isBlank()||primitive.isBoolean()&&!primitive.getAsBoolean())continue;if(key.equals("errorCode")&&(primitive.isNumber()||primitive.isString())&&primitive.getAsString().equals("0"))continue;}
            return true;
        }return false;
    }
    public enum ReconciliationStatus{MATCHED,NOT_FOUND,CONFLICT}
    public record OrderReconciliation(ReconciliationStatus status,RemoteOrder order,int candidateCount){}
    public record RemoteOrder(String externalOrderId,String remoteStatus,Instant createdAt){}
    private String text(JsonObject o,String...k){for(String x:k)if(o.has(x)&&!o.get(x).isJsonNull())return o.get(x).getAsString();return "";}
    private int integer(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():0;}
    private String resolveCisType(Settings settings,String gtin)throws Exception{
        Product stored=repository.findProduct(gtin).orElseGet(()->new com.vncode.app.features.kiz.KizRegistrationIdentityRepository().product(repository.shop().shopId(),gtin).orElseThrow(()->new IllegalArgumentException("GTIN is not registered for the selected shop.")));
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
