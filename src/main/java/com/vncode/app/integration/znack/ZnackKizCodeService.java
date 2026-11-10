package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.integration.znack.ZnackModels.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.io.IOException;

public class ZnackKizCodeService {
    private final ZnackApiClient api;private final ZnackAuthService auth;private final ZnackRepository repository;
    public ZnackKizCodeService(ZnackApiClient api,ZnackAuthService auth,ZnackRepository repository){this.api=api;this.auth=auth;this.repository=repository;}
    public int download(Settings s,long id)throws Exception{
        ZnackSafety.requireSigned(s,true);
        KizOrder order=repository.findOrder(id).orElseThrow();
        int remaining=Math.max(0,order.quantity()-repository.findCodes(id).size());
        if(remaining==0){
            repository.updateOrder(id,null,"READY",OrderStatus.CODES_DOWNLOADED,null);
            return 0;
        }
        JsonElement response=api.codes(s.resolvedSuzBaseUrl(),auth.suzToken(s),s.omsId(),
                order.externalOrderId(),remaining,order.gtin());
        int inserted=storeResponse(id,order.gtin(),response,null);
        int stored=repository.findCodes(id).size();
        // A nonempty response can still be a partial block. Only the durable count proves that
        // every purchased code was received; callers must not mistake partial delivery for done.
        repository.updateOrder(id,null,stored>=order.quantity()?"READY":order.remoteStatus(),
                stored>=order.quantity()?OrderStatus.CODES_DOWNLOADED:OrderStatus.WAITING_CODES,null);
        repository.log("DOWNLOAD_CODES",String.valueOf(id),"INFO","Downloaded "+inserted+" new codes",200);
        return inserted;
    }

    /**
     * Replays code packages already issued by SUZ. This is the only safe recovery after VN code did
     * not persist a successful /codes response: it never creates a new order and INSERT OR IGNORE
     * keeps the operation idempotent.
     */
    public int recoverIssuedBlocks(Settings s,long id)throws Exception{
        ZnackSafety.requireSigned(s,true);
        KizOrder order=repository.findOrder(id).orElseThrow();
        var storedCodes=repository.findCodes(id);
        requireStoredCodeIdentity(order,storedCodes);
        if(storedCodes.size()>=order.quantity())return 0;
        if(s.omsId()==null||s.omsId().isBlank()||order.externalOrderId()==null||order.externalOrderId().isBlank())throw invalidRecovery();
        String token=auth.suzToken(s);
        JsonElement response=api.codeBlocks(s.resolvedSuzBaseUrl(),token,s.omsId(),
                order.externalOrderId(),order.gtin());
        requireResponseIdentity(response,s,order,null);
        var expectedQuantities=new HashMap<String,Integer>();
        collectBlockQuantities(response,order.quantity(),expectedQuantities);
        var storedQuantities=new HashMap<String,Integer>();
        for(var code:storedCodes)if(code.blockId()!=null&&!code.blockId().isBlank())storedQuantities.merge(identityKey(code.blockId()),1,Integer::sum);
        int inserted=0,stored=storedCodes.size();
        for(String blockId:blockIds(response)){
            if(stored>=order.quantity())break;
            // A block ID on one stored code is not a receipt proving the whole block was saved.
            // Retry already-issued packages while incomplete; INSERT OR IGNORE retains both
            // duplicate identities and existing reservation/consumption/legal statuses.
            if(blockId.isBlank())continue;
            Integer expected=expectedQuantities.get(identityKey(blockId));
            if(expected!=null&&expected.equals(storedQuantities.getOrDefault(identityKey(blockId),0)))continue;
            JsonElement block=api.retryCodeBlock(s.resolvedSuzBaseUrl(),token,s.omsId(),blockId);
            requireResponseIdentity(block,s,order,blockId);
            if(expected!=null&&new java.util.HashSet<>(codes(block)).size()>expected)throw invalidRecovery();
            for(String raw:codes(block))requireRawCodeIdentity(order,raw);
            int received=storeResponse(id,order.gtin(),block,blockId);inserted+=received;stored+=received;
        }
        repository.log("RECOVER_CODE_BLOCKS",String.valueOf(id),"INFO","Recovered "+inserted+" new codes",200);
        return inserted;
    }

    /** Current SUZ responses identify OMS/order/GTIN; legacy integrations may omit fields.
     * Every supplied identity must match the original request. Check the whole block list
     * before replaying any package, without inventing missing fields as fresh proof. */
    public static final class RecoveryIdentityException extends IOException{
        private RecoveryIdentityException(){super("SUZ_CODE_BLOCK_RECOVERY_IDENTITY_INVALID");}
    }
    static void requireStoredCodeIdentity(KizOrder order,List<KizCode> stored)throws RecoveryIdentityException{
        for(var code:stored){
            try{if(code.orderId()!=order.id()||!GtinNormalizer.normalize(order.gtin()).equals(GtinNormalizer.normalize(code.gtin())))throw invalidRecovery();}
            catch(IllegalArgumentException invalid){throw invalidRecovery();}
            requireRawCodeIdentity(order,code.rawCode());
        }
    }
    private static void requireRawCodeIdentity(KizOrder order,String raw)throws RecoveryIdentityException{
        if(raw==null||raw.isBlank())throw invalidRecovery();
        com.vncode.app.features.kiz.KizMark mark;
        try{mark=com.vncode.app.features.kiz.KizMark.parse(raw);}
        catch(IllegalArgumentException legacy){return;/* Preserve existing opaque SUZ encodings; this does not verify their legality. */}
        if(!GtinNormalizer.normalize(order.gtin()).equals(GtinNormalizer.normalize(mark.gtin())))throw invalidRecovery();
    }
    private static void requireResponseIdentity(JsonElement response,Settings settings,KizOrder order,String blockId)throws IOException{
        if(response==null||response.isJsonNull())throw invalidRecovery();
        if(response.isJsonArray()){
            for(var value:response.getAsJsonArray())requireResponseIdentity(value,settings,order,blockId);
            return;
        }
        if(!response.isJsonObject())return; // Bare issued-block IDs/codes are supported.
        for(var entry:response.getAsJsonObject().entrySet()){
            String name=entry.getKey().replace("_","").toLowerCase(java.util.Locale.ROOT);
            var value=entry.getValue();String expected=switch(name){case "omsid"->settings.omsId();case "orderid"->order.externalOrderId();case "gtin","productgtin"->GtinNormalizer.normalize(order.gtin());case "blockid"->blockId;default->null;};
            if(name.equals("blockid")&&expected==null&&(value==null||!value.isJsonPrimitive()||value.getAsJsonPrimitive().isBoolean()||value.getAsString().isBlank()))throw invalidRecovery();
            if(expected!=null){
                if(value==null||!value.isJsonPrimitive()||value.getAsJsonPrimitive().isBoolean()||value.getAsString().isBlank())throw invalidRecovery();
                String actual=value.getAsString();
                if(name.equals("gtin")||name.equals("productgtin"))try{actual=GtinNormalizer.normalize(actual);}catch(IllegalArgumentException invalid){throw invalidRecovery();}
                if(!identityKey(expected).equals(identityKey(actual)))throw invalidRecovery();
            }
            if(value!=null&&!value.isJsonNull()&&(value.isJsonObject()||value.isJsonArray()))requireResponseIdentity(value,settings,order,blockId);
        }
    }
    private static void collectBlockQuantities(JsonElement response,int orderQuantity,Map<String,Integer> counts)throws IOException{
        if(response.isJsonArray()){for(var entry:response.getAsJsonArray())collectBlockQuantities(entry,orderQuantity,counts);return;}
        if(!response.isJsonObject())return;
        String block=null;JsonElement quantity=null;
        for(var entry:response.getAsJsonObject().entrySet()){
            String name=entry.getKey().replace("_","").toLowerCase(java.util.Locale.ROOT);
            if(name.equals("blockid"))block=entry.getValue().getAsString();
            if(name.equals("quantity"))quantity=entry.getValue();
        }
        if(block!=null&&quantity!=null){
            int expected;
            try{if(!quantity.isJsonPrimitive()||!quantity.getAsJsonPrimitive().isNumber())throw new ArithmeticException();expected=quantity.getAsBigDecimal().intValueExact();if(expected<=0||expected>orderQuantity)throw new ArithmeticException();}
            catch(RuntimeException invalid){throw invalidRecovery();}
            Integer previous=counts.putIfAbsent(identityKey(block),expected);if(previous!=null&&previous!=expected)throw invalidRecovery();
        }
        for(var entry:response.getAsJsonObject().entrySet())if(entry.getValue().isJsonArray()||entry.getValue().isJsonObject())collectBlockQuantities(entry.getValue(),orderQuantity,counts);
    }
    private static String identityKey(String value){return value!=null&&value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")?value.toLowerCase(java.util.Locale.ROOT):value;}
    private static RecoveryIdentityException invalidRecovery(){return new RecoveryIdentityException();}

    private int storeResponse(long orderId,String gtin,JsonElement response,String fallbackBlockId){
        String blockId=fallbackBlockId;
        if(response!=null&&response.isJsonObject()){
            JsonObject object=response.getAsJsonObject();
            if(object.has("blockId")&&!object.get("blockId").isJsonNull())blockId=object.get("blockId").getAsString();
        }
        return repository.insertCodes(orderId,gtin,new DownloadedCodes(codes(response),blockId));
    }

    private List<String> codes(JsonElement response){
        if(response==null||response.isJsonNull())return List.of();
        JsonArray array;
        if(response.isJsonArray())array=response.getAsJsonArray();
        else if(response.isJsonObject()&&response.getAsJsonObject().has("codes")
                &&response.getAsJsonObject().get("codes").isJsonArray())array=response.getAsJsonObject().getAsJsonArray("codes");
        else return List.of();
        List<String> result=new ArrayList<>();
        for(JsonElement element:array){
            if(element.isJsonPrimitive())result.add(element.getAsString());
            else if(element.isJsonObject()&&element.getAsJsonObject().has("cis"))result.add(element.getAsJsonObject().get("cis").getAsString());
        }
        return result;
    }

    private List<String> blockIds(JsonElement response){
        Set<String> result=new LinkedHashSet<>();
        collectBlockIds(response,response!=null&&response.isJsonArray(),result);
        return List.copyOf(result);
    }

    private void collectBlockIds(JsonElement element,boolean blockCollection,Set<String> result){
        if(element==null||element.isJsonNull())return;
        if(element.isJsonPrimitive()){
            if(blockCollection){
                String value=element.getAsString().trim();
                if(!value.isBlank())result.add(value);
            }
            return;
        }
        if(element.isJsonArray()){
            for(JsonElement item:element.getAsJsonArray())collectBlockIds(item,blockCollection,result);
            return;
        }
        for(var entry:element.getAsJsonObject().entrySet()){
            String key=entry.getKey().replace("_","").toLowerCase(java.util.Locale.ROOT);
            if("blockid".equals(key)&&entry.getValue().isJsonPrimitive()){
                String value=entry.getValue().getAsString().trim();
                if(!value.isBlank())result.add(value);
            }else{
                boolean nestedCollection="blocks".equals(key)||"blockids".equals(key)
                        ||"codeblocks".equals(key)||"packages".equals(key);
                collectBlockIds(entry.getValue(),nestedCollection,result);
            }
        }
    }
}
