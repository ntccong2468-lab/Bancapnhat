package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Exact document review precedes signing. Reconciliation never sends a mutation. */
public final class Gs1ApplicationService {
    public static final class PreparedApplication {
        private final String requestId,inn,xml,description;
        private final JsonObject form;
        private PreparedApplication(String requestId,String inn,JsonObject form,String xml,String description){this.requestId=requestId;this.inn=inn;this.form=form.deepCopy();this.xml=xml;this.description=description;}
        public String requestId(){return requestId;}
        public String inn(){return inn;}
        public String xml(){return xml;}
        public String description(){return description;}
        public String xmlDigest(){return digestText(xml);}
        @Override public String toString(){return "PreparedGs1Application[id="+requestId+"]";}
    }
    private final Gs1Repository repository;
    public Gs1ApplicationService(Gs1Repository repository){this.repository=Objects.requireNonNull(repository);}
    /** Called only after confirmation to save a draft; this stage does not sign or submit it. */
    public PreparedApplication prepareSigning(NationalCatalogGs1Client.Session session,JsonObject reviewed)throws IOException{
        JsonObject snapshot=reviewed.deepCopy();
        if(!session.inn().equals(owner(snapshot)))throw new SecurityException("Application enterprise differs from reviewed enterprise");
        if(repository.hasBlockingOperation(session.inn(),"JOIN"))throw new IllegalStateException("Previous GS1 submission requires status reconciliation");
        session.saveForm(snapshot);
        if(!matchesReviewed(snapshot,session.form()))throw new IOException("Catalog saved fields differ from the reviewed application");
        String xml=session.signingDocument();
        String description=Gs1SigningDocument.validateAndDescribe(xml,snapshot);
        var checkpoint=repository.createRequest(session.inn(),"JOIN","","GS1 application",checkpoint(snapshot,xml));
        return new PreparedApplication(checkpoint.id(),session.inn(),snapshot,xml,description);
    }
    public Gs1Repository.State submit(NationalCatalogGs1Client.Session session,PreparedApplication reviewed,NationalCatalogGs1Client.XmlSigner signer)throws IOException{
        if(!session.inn().equals(reviewed.inn))throw new SecurityException("Wrong enterprise application");
        var request=repository.request(session.inn(),reviewed.requestId);
        if(!request.kind().equals("JOIN")||!request.body().equals(checkpoint(reviewed.form,reviewed.xml)))throw new IllegalStateException("Application changed after preview; review it again");
        if(request.state()!=Gs1Repository.State.DRAFT||repository.hasBlockingOperation(session.inn(),"JOIN"))throw new IllegalStateException("Previous GS1 submission requires status reconciliation");
        JsonObject current=session.form();
        if(!matchesReviewed(reviewed.form,current))throw new IllegalStateException("Application changed after document review; review it again");
        if(Boolean.TRUE.equals(booleanValue(current,"isSent")))return reconcile(session,reviewed.requestId);
        if(!repository.claimSend(session.inn(),reviewed.requestId))throw new IllegalStateException("Previous GS1 submission requires status reconciliation");
        try{
            session.submitDocument(reviewed.xml,signer);
            return reconcile(session,reviewed.requestId);
        }catch(NationalCatalogGs1Client.SigningFailedException notSubmitted){
            repository.releaseUnsent(session.inn(),reviewed.requestId);
            throw new IOException("gs1.sign_not_sent");
        }catch(IOException|RuntimeException error){
            repository.setState(session.inn(),reviewed.requestId,Gs1Repository.State.RECONCILE_REQUIRED);
            throw error;
        }
    }
    public Gs1Repository.State reconcile(NationalCatalogGs1Client.Session session,String requestId)throws IOException{
        var request=repository.request(session.inn(),requestId);
        if(!request.kind().equals("JOIN"))throw new IllegalArgumentException("Select a GS1 application");
        JsonObject form=session.form();
        if(!session.inn().equals(owner(form)))throw new IOException("GS1 application owner could not be confirmed");
        var state=Gs1Repository.State.RECONCILE_REQUIRED;
        if(!NationalCatalogGs1Client.text(form,"errorText").isBlank())state=Gs1Repository.State.REJECTED;
        else if(Boolean.TRUE.equals(booleanValue(form,"isSent")))state=Gs1Repository.State.SUBMITTED;
        repository.setState(session.inn(),requestId,state);return state;
    }
    private static Boolean booleanValue(JsonObject object,String key){JsonElement value=object.get(key);return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()?value.getAsBoolean():null;}
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
    private static String checkpoint(JsonObject form,String xml){
        JsonObject copy=form.deepCopy();for(String key:List.of("isSent","errorText","expiryDate"))copy.remove(key);
        return digestText(canonical(copy))+":"+digestText(xml);
    }
    private static String digestText(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
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
