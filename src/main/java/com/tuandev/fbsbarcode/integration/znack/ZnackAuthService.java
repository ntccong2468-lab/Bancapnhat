package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public class ZnackAuthService {
    private final ZnackApiClient api;
    private final ZnackSignatureProvider signer;
    private volatile Token trueToken;
    private volatile Token suzToken;
    private volatile String authenticatedParticipantInn;

    public ZnackAuthService(ZnackApiClient api, ZnackSignatureProvider signer) { this.api=api; this.signer=signer; }
    public String trueApiToken(ZnackModels.Settings s)throws Exception{ZnackSafety.requireSigned(s,false);return token(s.resolvedTrueApiBaseUrl(),"",s.participantInn(),false);}
    public String suzToken(ZnackModels.Settings s)throws Exception{ZnackSafety.requireSigned(s,true);return token(s.resolvedTrueApiBaseUrl(),s.omsConnection(),s.participantInn(),true);}
    public String authenticatedParticipantInn(){return authenticatedParticipantInn;}
    public String resolvedParticipantInn(ZnackModels.Settings s){
        if(s.participantInn()!=null&&!s.participantInn().isBlank())return s.participantInn().trim();
        if(authenticatedParticipantInn!=null&&!authenticatedParticipantInn.isBlank())return authenticatedParticipantInn;
        return certificateInn(s.certificateMetadataJson());
    }
    private synchronized String token(String base,String connection,String inn,boolean suz)throws Exception{
        Token cached=suz?suzToken:trueToken;if(cached!=null&&cached.expiresAt.isAfter(Instant.now().plusSeconds(30)))return cached.value;
        JsonObject challenge=api.authKey(base);
        byte[] signed=signer.sign(challenge.get("data").getAsString().getBytes(StandardCharsets.UTF_8), ZnackSignatureContext.AUTH_CHALLENGE).cms();
        JsonObject request=new JsonObject();request.addProperty("uuid",challenge.get("uuid").getAsString());request.addProperty("data",Base64.getEncoder().encodeToString(signed));
        if(inn!=null&&!inn.isBlank())request.addProperty("inn",inn);
        JsonObject response=api.signIn(base,connection,request);
        String value=first(response,"clientToken","token","sessionToken","jwt");long seconds=response.has("expiresIn")?response.get("expiresIn").getAsLong():36000;
        Token token=new Token(value,Instant.now().plusSeconds(seconds));if(suz)suzToken=token;else trueToken=token;
        String derived=participantInn(value);if(derived!=null&&!derived.isBlank())authenticatedParticipantInn=derived;
        return value;
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
}
