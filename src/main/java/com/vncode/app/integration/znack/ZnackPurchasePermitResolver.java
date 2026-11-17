package com.vncode.app.integration.znack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

/** Registry lookup after purchase, with owner/participant fallback and explicit unknown results. */
final class ZnackPurchasePermitResolver {
    private ZnackPurchasePermitResolver() {}

    static Result resolve(ZnackApiClient api,String base,String token,String gtin,
                          String configuredOwner,String participant)throws IOException{
        var candidates=new LinkedHashSet<String>();
        candidates.add(normalize(configuredOwner));
        if(!normalize(participant).isEmpty())candidates.add(normalize(participant));
        candidates.add("");
        boolean pending=false;
        IOException failure=null;
        for(String inn:candidates){
            try{
                JsonElement response=api.permitDocuments(base,token,gtin,inn);
                if(ZnackPermitDocumentParser.registryLookupPending(response)){pending=true;continue;}
                if(!authoritative(response))throw new IOException("National Catalog permit response is missing its documents list.");
                List<GoodsDocument> documents=ZnackPermitDocumentParser.activeFromRegistry(response);
                if(!documents.isEmpty())return new Result(documents,false);
            }catch(IOException error){
                // Changing the owner filter cannot repair an expired session.
                if(error instanceof ZnackApiClient.ZnackApiException rejected&&rejected.statusCode()==401)throw error;
                if(failure==null)failure=error;else failure.addSuppressed(error);
            }
        }
        if(pending)return new Result(List.of(),true);
        if(failure!=null)throw failure;
        return new Result(List.of(),false);
    }

    private static String normalize(String value){return value==null?"":value.strip();}
    private static boolean authoritative(JsonElement value){
        if(value==null||value.isJsonNull())return false;
        if(value.isJsonArray())return true; // Established legacy read adapter.
        if(!value.isJsonObject())return false;
        JsonObject root=value.getAsJsonObject();
        if(root.has("documents")&&root.get("documents").isJsonArray())return true;
        return root.has("result")&&root.get("result").isJsonObject()
                &&root.getAsJsonObject("result").has("documents")
                &&root.getAsJsonObject("result").get("documents").isJsonArray();
    }
    record Result(List<GoodsDocument> documents,boolean pending){
        Result{documents=List.copyOf(documents);}
    }
}
