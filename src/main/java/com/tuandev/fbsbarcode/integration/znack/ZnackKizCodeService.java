package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        if(!codes(response).isEmpty())repository.updateOrder(id,null,"READY",OrderStatus.CODES_DOWNLOADED,null);
        repository.log("DOWNLOAD_CODES",String.valueOf(id),"INFO","Downloaded "+inserted+" new codes",200);
        return inserted;
    }

    /**
     * Replays code packages already issued by SUZ. This is the only safe recovery after WCode did
     * not persist a successful /codes response: it never creates a new order and INSERT OR IGNORE
     * keeps the operation idempotent.
     */
    public int recoverIssuedBlocks(Settings s,long id)throws Exception{
        ZnackSafety.requireSigned(s,true);
        KizOrder order=repository.findOrder(id).orElseThrow();
        String token=auth.suzToken(s);
        JsonElement response=api.codeBlocks(s.resolvedSuzBaseUrl(),token,s.omsId(),
                order.externalOrderId(),order.gtin());
        Set<String> storedBlocks=new HashSet<>();
        for(KizCode code:repository.findCodes(id))if(code.blockId()!=null&&!code.blockId().isBlank())storedBlocks.add(code.blockId());
        int inserted=0;
        for(String blockId:blockIds(response)){
            if(blockId.isBlank()||storedBlocks.contains(blockId))continue;
            JsonElement block=api.retryCodeBlock(s.resolvedSuzBaseUrl(),token,s.omsId(),blockId);
            inserted+=storeResponse(id,order.gtin(),block,blockId);
            storedBlocks.add(blockId);
        }
        repository.log("RECOVER_CODE_BLOCKS",String.valueOf(id),"INFO","Recovered "+inserted+" new codes",200);
        return inserted;
    }

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
