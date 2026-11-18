package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.znack.registration.CatalogFeedback;
import java.time.Instant;
import java.util.Objects;

/** Minimal application evidence; no full enterprise form or signing document is cached. */
public record Gs1ApplicationStatus(State state,String reason) {
    public enum State { UNKNOWN, DRAFT, SUBMITTED, REJECTED }
    public Gs1ApplicationStatus {
        state=Objects.requireNonNullElse(state,State.UNKNOWN);
        reason=state==State.REJECTED?CatalogFeedback.display(Objects.requireNonNullElse(reason,"")).strip():"";
        if(state==State.REJECTED&&reason.isBlank())reason="GS1_APPLICATION_REJECTED";
    }
    public static Gs1ApplicationStatus unknown(){return new Gs1ApplicationStatus(State.UNKNOWN,"");}
    /** Error text takes precedence over isSent, as in the official Catalog form. */
    public static Gs1ApplicationStatus read(JsonObject form,boolean newApplication){
        if(form==null)return unknown();
        var sent=form.get("isSent");var error=form.get("errorText");
        if(sent!=null&&!sent.isJsonNull()&&!(sent.isJsonPrimitive()&&sent.getAsJsonPrimitive().isBoolean()))return unknown();
        if(error!=null&&!error.isJsonNull()&&!(error.isJsonPrimitive()&&error.getAsJsonPrimitive().isString()))return unknown();
        if(error!=null&&!error.isJsonNull()&&!error.getAsString().isBlank())return new Gs1ApplicationStatus(State.REJECTED,error.getAsString());
        return fromFlags(sent==null||sent.isJsonNull()?null:sent.getAsBoolean(),newApplication);
    }
    static Gs1ApplicationStatus fromFlags(Boolean sent,boolean newApplication){
        return new Gs1ApplicationStatus(Boolean.TRUE.equals(sent)?State.SUBMITTED:Boolean.FALSE.equals(sent)||newApplication?State.DRAFT:State.UNKNOWN,"");
    }
    /** UNKNOWN has no usable form assertion, even if legacy cache retained an isSent bit. */
    public JsonObject form(String inn,String email){
        if(state==State.UNKNOWN)return null;
        var form=new JsonObject();var applicant=new JsonObject();applicant.addProperty("inn",inn);applicant.addProperty("email",Objects.requireNonNullElse(email,""));form.add("applicant",applicant);
        form.addProperty("isSent",state==State.SUBMITTED);
        if(state==State.REJECTED)form.addProperty("errorText",reason);
        return form;
    }
    /** Binding the evidence to its source timestamp invalidates stale sidecars after an older binary writes. */
    String stored(Instant observedAt){
        var value=new JsonObject();value.addProperty("state",state.name());value.addProperty("reason",reason);value.addProperty("observedAt",observedAt.toString());return value.toString();
    }
    static Gs1ApplicationStatus restore(String payload,Instant observedAt){
        if(payload==null||payload.length()>8192||observedAt==null)return unknown();
        try{
            var value=JsonParser.parseString(payload).getAsJsonObject();
            for(String key:java.util.List.of("state","reason","observedAt"))if(!value.has(key)||!value.get(key).isJsonPrimitive()||!value.getAsJsonPrimitive(key).isString())return unknown();
            if(!observedAt.equals(Instant.parse(NationalCatalogGs1Client.text(value,"observedAt"))))return unknown();
            return new Gs1ApplicationStatus(State.valueOf(NationalCatalogGs1Client.text(value,"state")),NationalCatalogGs1Client.text(value,"reason"));
        }catch(RuntimeException invalid){return unknown();}
    }
    @Override public String toString(){return "Gs1ApplicationStatus[state="+state+"]";}
}
