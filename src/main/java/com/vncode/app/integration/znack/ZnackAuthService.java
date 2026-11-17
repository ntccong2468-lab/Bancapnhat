package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class ZnackAuthService {
    private final ZnackApiClient api;
    private final ZnackSignatureProvider signer;
    private final ConcurrentHashMap<SessionKey,Token> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AccountKey,String> participantByCertificate = new ConcurrentHashMap<>();
    private volatile String authenticatedParticipantInn;

    public ZnackAuthService(ZnackApiClient api, ZnackSignatureProvider signer) { this.api=api; this.signer=signer; }
    public String trueApiToken(ZnackModels.Settings s)throws Exception{ZnackSafety.requireSigned(s,false);return token(s,false);}
    public String suzToken(ZnackModels.Settings s)throws Exception{ZnackSafety.requireSigned(s,true);return token(s,true);}
    public String authenticatedParticipantInn(){return authenticatedParticipantInn;}
    public String resolvedParticipantInn(ZnackModels.Settings s){
        if(s.participantInn()!=null&&!s.participantInn().isBlank())return s.participantInn().trim();
        String authenticated=participantByCertificate.get(accountKey(s));
        if(authenticated!=null&&!authenticated.isBlank())return authenticated;
        return certificateInn(s.certificateMetadataJson());
    }
    private synchronized String token(ZnackModels.Settings s,boolean suz)throws Exception{
        SessionKey key=sessionKey(s,suz);
        Token cached=sessions.get(key);if(cached!=null&&cached.expiresAt.isAfter(Instant.now().plusSeconds(30)))return cached.value;
        String base=key.base(),connection=key.connection(),inn=key.inn();
        JsonObject challenge=api.authKey(base);
        byte[] signed=signer.sign(challenge.get("data").getAsString().getBytes(StandardCharsets.UTF_8), ZnackSignatureContext.AUTH_CHALLENGE).cms();
        JsonObject request=new JsonObject();request.addProperty("uuid",challenge.get("uuid").getAsString());request.addProperty("data",Base64.getEncoder().encodeToString(signed));
        if(inn!=null&&!inn.isBlank())request.addProperty("inn",inn);
        JsonObject response=api.signIn(base,connection,request);
        String value=first(response,"clientToken","token","sessionToken","jwt");
        if(value.isBlank())throw new IllegalStateException("Znack authentication response contained an empty token.");
        long seconds=response.has("expiresIn")?response.get("expiresIn").getAsLong():36000;
        if(seconds<=0)throw new IllegalStateException("Znack authentication response contained an invalid lifetime.");
        Token token=new Token(value,Instant.now().plusSeconds(seconds));sessions.put(key,token);
        String derived=participantInn(value);if(derived!=null&&!derived.isBlank()){
            authenticatedParticipantInn=derived;participantByCertificate.put(accountKey(s),derived);
        }
        return value;
    }

    /** Invalidates only this account's still-matching rejected SUZ session, never True API. */
    public synchronized boolean invalidateSuzToken(ZnackModels.Settings settings,String rejectedToken) {
        return invalidate(settings,rejectedToken,true);
    }

    public synchronized boolean invalidateTrueApiToken(ZnackModels.Settings settings,String rejectedToken) {
        return invalidate(settings,rejectedToken,false);
    }

    private boolean invalidate(ZnackModels.Settings settings,String rejectedToken,boolean suz) {
        if(rejectedToken==null)return false;
        SessionKey key=sessionKey(settings,suz);Token cached=sessions.get(key);
        return cached!=null&&rejectedToken.equals(cached.value())&&sessions.remove(key,cached);
    }

    @FunctionalInterface public interface SuzRead<T> { T read(String clientToken)throws Exception; }

    /** One authentication refresh for a read-only request. Never use for order creation or GET codes. */
    public <T>T readWithSuzRecovery(ZnackModels.Settings settings,SuzRead<T> read)throws Exception {
        String token=suzToken(settings);
        try{return read.read(token);}
        catch(ZnackApiClient.ZnackApiException rejected){
            if(rejected.statusCode()!=401)throw rejected;
            invalidateSuzToken(settings,token);
            return read.read(suzToken(settings));
        }
    }

    @FunctionalInterface public interface TrueApiRead<T> { T read(String token)throws Exception; }

    /** One refresh for read-only True API status/registry requests, never document submission. */
    public <T>T readWithTrueApiRecovery(ZnackModels.Settings settings,TrueApiRead<T> read)throws Exception {
        String token=trueApiToken(settings);
        try{return read.read(token);}
        catch(ZnackApiClient.ZnackApiException rejected){
            if(rejected.statusCode()!=401)throw rejected;
            invalidateTrueApiToken(settings,token);
            return read.read(trueApiToken(settings));
        }
    }

    private static SessionKey sessionKey(ZnackModels.Settings s,boolean suz){
        return new SessionKey(s.resolvedTrueApiBaseUrl(),suz?s.resolvedSuzBaseUrl():"",
                suz?Objects.toString(s.omsId(),""):"",suz?Objects.toString(s.omsConnection(),""):"",
                Objects.toString(s.participantInn(),"").trim(),certificate(s),suz);
    }
    private static AccountKey accountKey(ZnackModels.Settings s){
        return new AccountKey(s.resolvedTrueApiBaseUrl(),Objects.toString(s.omsConnection(),""),
                Objects.toString(s.participantInn(),"").trim(),certificate(s));
    }
    private static String certificate(ZnackModels.Settings s){
        return Objects.toString(s.signerCertificate(),"").replaceAll("\\s+","").toUpperCase(java.util.Locale.ROOT);
    }
    private String first(JsonObject o,String... keys){for(String k:keys)if(o.has(k)&&!o.get(k).isJsonNull())return o.get(k).getAsString();throw new IllegalStateException("Znack authentication response did not contain a token.");}
    static String participantInn(String jwt){
        try{
            String[] parts=jwt.split("\\.");if(parts.length<2)return null;
            JsonElement claims=JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8));
            return findInn(claims);
        }catch(Exception ignored){return null;}
    }
    static String certificateInn(String metadataJson){
        try{
            JsonObject metadata=JsonParser.parseString(metadataJson).getAsJsonObject();
            String inn=metadata.has("inn")?metadata.get("inn").getAsString():"";
            return inn.matches("\\d{10}|\\d{12}")?inn:null;
        }catch(Exception ignored){return null;}
    }
    private static String findInn(JsonElement element){
        if(element==null||element.isJsonNull())return null;
        if(element.isJsonObject()){
            JsonObject object=element.getAsJsonObject();
            for(String key:new String[]{"participant_inn","participantInn","inn","userInn","user_inn"}){
                if(object.has(key)&&object.get(key).isJsonPrimitive()){
                    String value=object.get(key).getAsString();if(value.matches("\\d{10}|\\d{12}"))return value;
                }
            }
            for(var entry:object.entrySet()){String found=findInn(entry.getValue());if(found!=null)return found;}
        }else if(element.isJsonArray())for(JsonElement item:element.getAsJsonArray()){String found=findInn(item);if(found!=null)return found;}
        return null;
    }
    private record Token(String value,Instant expiresAt){}
    private record SessionKey(String base,String suzBase,String omsId,String connection,String inn,String certificate,boolean suz){}
    private record AccountKey(String base,String connection,String inn,String certificate){}
}
