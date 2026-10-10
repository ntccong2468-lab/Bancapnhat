package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One reviewed application, one send. Reconciliation never sends a mutation. */
public final class Gs1ApplicationService {
    private final Gs1Repository repository;
    public Gs1ApplicationService(Gs1Repository repository){this.repository=Objects.requireNonNull(repository);}
    public Gs1Repository.Request prepare(String inn,JsonObject reviewed){
        if(!inn.equals(owner(reviewed)))throw new SecurityException("Application enterprise differs from reviewed enterprise");
        return repository.createRequest(inn,"JOIN","", "GS1 application",digest(reviewed));
    }
    public Gs1Repository.State submit(NationalCatalogGs1Client.Session session,String requestId,JsonObject form,NationalCatalogGs1Client.XmlSigner signer)throws IOException{
        var request=repository.request(session.inn(),requestId);
        if(!request.kind().equals("JOIN")||!request.body().equals(digest(form)))throw new IllegalStateException("Application changed after preview; review it again");
        if(!session.inn().equals(owner(form)))throw new SecurityException("Wrong enterprise application");
        if(!repository.claimSend(session.inn(),requestId))throw new IllegalStateException("Previous GS1 submission requires status reconciliation");
        try{
            session.saveForm(form);
            JsonObject saved=session.form();
            if(!matchesReviewed(form,saved))throw new IOException("Catalog saved fields differ from the reviewed application");
            session.submit(signer);
            return reconcile(session,requestId);
        }catch(IOException|RuntimeException error){
            repository.setState(session.inn(),requestId,Gs1Repository.State.RECONCILE_REQUIRED);
            throw error;
        }
    }
    public Gs1Repository.State reconcile(NationalCatalogGs1Client.Session session,String requestId)throws IOException{
        repository.request(session.inn(),requestId);
        JsonObject form=session.form();
        if(!session.inn().equals(owner(form)))throw new IOException("GS1 application owner could not be confirmed");
        var state=Gs1Repository.State.RECONCILE_REQUIRED;
        JsonElement sent=form.get("isSent");
        if(!NationalCatalogGs1Client.text(form,"errorText").isBlank())state=Gs1Repository.State.REJECTED;
        else if(sent!=null&&sent.isJsonPrimitive()&&sent.getAsJsonPrimitive().isBoolean()&&sent.getAsBoolean())state=Gs1Repository.State.SUBMITTED;
        repository.setState(session.inn(),requestId,state);return state;
    }
    private static String owner(JsonObject form){return form.has("applicant")&&form.get("applicant").isJsonObject()?NationalCatalogGs1Client.text(form.getAsJsonObject("applicant"),"inn"):"";}
    private static boolean matchesReviewed(JsonElement reviewed,JsonElement saved){
        if(reviewed.isJsonObject()){
            if(!saved.isJsonObject())return false;
            for(var entry:reviewed.getAsJsonObject().entrySet()){
                if(Set.of("isSent","errorText","expiryDate").contains(entry.getKey()))continue;
                if(!saved.getAsJsonObject().has(entry.getKey())||!matchesReviewed(entry.getValue(),saved.getAsJsonObject().get(entry.getKey())))return false;
            }
            return true;
        }
        return reviewed.equals(saved);
    }
    private static String digest(JsonObject form){
        JsonObject copy=form.deepCopy();for(String key:List.of("isSent","errorText","expiryDate"))copy.remove(key);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(copy).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String canonical(JsonElement value){
        if(value.isJsonObject()){
            JsonObject sorted=new JsonObject();value.getAsJsonObject().keySet().stream().sorted().forEach(k->sorted.add(k,JsonParser.parseString(canonical(value.getAsJsonObject().get(k)))));return sorted.toString();
        }
        if(value.isJsonArray()){JsonArray array=new JsonArray();value.getAsJsonArray().forEach(v->array.add(JsonParser.parseString(canonical(v))));return array.toString();}
        return value.toString();
    }
}
