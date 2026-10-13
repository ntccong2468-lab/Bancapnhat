package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
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
        JsonObject snapshot=reviewed.deepCopy();saveReviewedDraft(session,snapshot);
        String xml=session.signingDocument();
        String description=Gs1SigningDocument.validateAndDescribe(xml,snapshot);
        LicenseRuntime.require(Permission.GS1);
        var checkpoint=repository.createRequest(session.inn(),"JOIN","","GS1 application",checkpoint(snapshot,xml));
        return new PreparedApplication(checkpoint.id(),session.inn(),snapshot,xml,description);
    }
    /** Shared guard for save-only editors and signing preparation; stale editors cannot overwrite sent forms. */
    public JsonObject saveReviewedDraft(NationalCatalogGs1Client.Session session,JsonObject reviewed)throws IOException{
        LicenseRuntime.require(Permission.GS1);
        JsonObject snapshot=reviewed.deepCopy();
        if(!session.inn().equals(owner(snapshot)))throw new SecurityException("Application enterprise differs from reviewed enterprise");
        if(repository.hasBlockingOperation(session.inn(),"JOIN"))throw new IllegalStateException("Previous GS1 submission requires status reconciliation");
        JsonObject current=session.form();
        if(!current.entrySet().isEmpty()&&!session.inn().equals(owner(current)))throw new IOException("GS1 application owner could not be confirmed");
        if(submitted(current))throw new IllegalStateException("gs1.application_submitted");
        LicenseRuntime.require(Permission.GS1);
        session.saveForm(snapshot);
        JsonObject saved=session.form();
        if(submitted(saved))throw new IllegalStateException("gs1.application_submitted");
        if(!matchesReviewed(snapshot,saved))throw new IOException("Catalog saved fields differ from the reviewed application");
        return saved;
    }
    public Gs1Repository.State submit(NationalCatalogGs1Client.Session session,PreparedApplication reviewed,NationalCatalogGs1Client.XmlSigner signer)throws IOException{
        if(!session.inn().equals(reviewed.inn))throw new SecurityException("Wrong enterprise application");
        var request=repository.request(session.inn(),reviewed.requestId);
        if(!request.kind().equals("JOIN")||!request.body().equals(checkpoint(reviewed.form,reviewed.xml)))throw new IllegalStateException("Application changed after preview; review it again");
        if(request.state()!=Gs1Repository.State.DRAFT||repository.hasBlockingOperation(session.inn(),"JOIN"))throw new IllegalStateException("Previous GS1 submission requires status reconciliation");
        JsonObject current=session.form();
        if(!matchesReviewed(reviewed.form,current))throw new IllegalStateException("Application changed after document review; review it again");
        if(submitted(current))return reconcile(session,reviewed.requestId);
        LicenseRuntime.require(Permission.GS1);
        if(!repository.claimSend(session.inn(),reviewed.requestId))throw new IllegalStateException("Previous GS1 submission requires status reconciliation");
        var denied=new java.util.concurrent.atomic.AtomicReference<LicenseDeniedException>();
        try{
            LicenseRuntime.require(Permission.GS1);
            session.submitDocument(reviewed.xml,xml->{
                try{
                    LicenseRuntime.require(Permission.GS1);
                    String signed=signer.sign(xml);
                    // Signing can wait for a certificate prompt. Check again before the HTTP send.
                    LicenseRuntime.require(Permission.GS1);
                    return signed;
                }catch(LicenseDeniedException denial){denied.set(denial);throw denial;}
            });
            return reconcile(session,reviewed.requestId);
        }catch(LicenseDeniedException denial){
            repository.releaseUnsent(session.inn(),reviewed.requestId);
            throw denial;
        }catch(NationalCatalogGs1Client.SigningFailedException notSubmitted){
            repository.releaseUnsent(session.inn(),reviewed.requestId);
            if(denied.get()!=null)throw denied.get();
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
        JsonElement error=form.get("errorText");
        if(error==null||error.isJsonNull()||error.isJsonPrimitive()&&error.getAsJsonPrimitive().isString()){
            if(rejected(form))state=Gs1Repository.State.REJECTED;
            else if(Boolean.TRUE.equals(booleanValue(form,"isSent")))state=Gs1Repository.State.SUBMITTED;
        }
        repository.setState(session.inn(),requestId,state);return state;
    }
    private static Boolean booleanValue(JsonObject object,String key){JsonElement value=object.get(key);return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()?value.getAsBoolean():null;}
    private static boolean rejected(JsonObject form){JsonElement error=form.get("errorText");return error!=null&&error.isJsonPrimitive()&&error.getAsJsonPrimitive().isString()&&!error.getAsString().isBlank();}
    private static boolean submitted(JsonObject form){return Boolean.TRUE.equals(booleanValue(form,"isSent"))&&!rejected(form);}
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
