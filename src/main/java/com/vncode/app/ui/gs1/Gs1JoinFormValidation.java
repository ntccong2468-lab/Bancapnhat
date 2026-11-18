package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import java.util.*;

/** Local format checks for the existing Catalog form; the server remains authoritative. */
final class Gs1JoinFormValidation {
    record Failure(int step, JsonObject data, String field) {}
    private Gs1JoinFormValidation() {}
    static Optional<Failure> first(JsonObject form,String inn){
        for(int step=0;step<5;step++){var failure=step(form,inn,step);if(failure.isPresent())return failure;}
        return Optional.empty();
    }
    static Optional<Failure> step(JsonObject form,String inn,int step){
        JsonObject applicant=object(form,"applicant"),bank=object(form,"bankDetails"),business=object(form,"companyDetails");
        if(step==0){
            var missing=missing(applicant,step,"companyName","companyNameShort","countryId","firstName","lastName","email","phone","ogrn","okved2","opf","position");
            if(missing.isPresent())return missing;
            if(!inn.equals(text(applicant,"inn")))return fail(step,applicant,"inn");
            if(!literalBoolean(applicant.get("isMarked")))return fail(step,applicant,"isMarked");
            if(!email(text(applicant,"email")))return fail(step,applicant,"email");
            if(!text(applicant,"ogrn").matches("[0-9]{13}|[0-9]{15}"))return fail(step,applicant,"ogrn");
            if(inn.length()==10&&!text(applicant,"kpp").matches("[0-9]{4}[A-Z0-9]{2}[0-9]{3}"))return fail(step,applicant,"kpp");
        }else if(step==1){
            var missing=missing(bank,step,"name","bik","settlementAccount");if(missing.isPresent())return missing;
            if(!text(bank,"bik").matches("[0-9]{9}"))return fail(step,bank,"bik");
            if(!text(bank,"settlementAccount").matches("[0-9]{20}"))return fail(step,bank,"settlementAccount");
            if(!literalTrue(bank.get("noCorrespondentAccount"))&&!text(bank,"correspondentAccount").matches("[0-9]{20}"))return fail(step,bank,"correspondentAccount");
        }else if(step==2){
            var addresses=object(form,"addresses");
            for(String key:List.of("legalAddress","physicalAddress")){var invalid=address(object(addresses,key));if(invalid.isPresent())return invalid;}
            var production=addresses.get("productionAddresses");
            if(production!=null&&!production.isJsonNull()){
                if(!production.isJsonArray())return fail(step,addresses,"productionAddresses");
                for(var item:production.getAsJsonArray()){
                    if(!item.isJsonObject())return fail(step,addresses,"productionAddresses");
                    var invalid=address(item.getAsJsonObject());if(invalid.isPresent())return invalid;
                }
            }
        }else if(step==3){
            var missing=missing(business,step,"enterpriseSector","mainProductGCP","obtainingDocumentsMethod","productsCountToMark");if(missing.isPresent())return missing;
            if("WORKFLOW_EL".equals(text(business,"obtainingDocumentsMethod"))&&text(business,"provider").isBlank())return fail(step,business,"provider");
            if(!Set.of("less1000","more1000").contains(text(business,"productsCountToMark")))return fail(step,business,"productsCountToMark");
            if(!identifiers(business.get("accountType")))return fail(step,business,"accountType");
            if(!checked(business.get("partisipationInGovernmentProjects")))return fail(step,business,"partisipationInGovernmentProjects");
            for(String key:List.of("implementationMethods","distributionChannels")){
                var group=object(business,key);
                if(!checked(group.get("fields")))return fail(step,business,key);
                boolean other=group.getAsJsonArray("fields").asList().stream().map(JsonElement::getAsJsonObject)
                        .anyMatch(choice->literalTrue(choice.get("checked"))&&Set.of("ENT_SELLW_OTHRES","ENT_DISTCH_OTHRES").contains(text(choice,"value")));
                if(other&&text(group,"moreText").isBlank())return fail(step,business,key);
            }
        }else if(step==4){
            var contacts=form.get("contacts");
            if(contacts==null||!contacts.isJsonArray()||contacts.getAsJsonArray().isEmpty())return fail(step,form,"contacts");
            for(var item:contacts.getAsJsonArray()){
                if(!item.isJsonObject())return fail(step,form,"contacts");
                var data=item.getAsJsonObject();var missing=missing(data,step,"lastName","firstName","email","phone","position");if(missing.isPresent())return missing;
                if(!email(text(data,"email")))return fail(step,data,"email");
            }
        }
        return Optional.empty();
    }
    private static Optional<Failure> address(JsonObject data){
        var missing=missing(data,2,"countryId","fullAddress");if(missing.isPresent())return missing;
        return "RU".equalsIgnoreCase(text(data,"countryId"))&&text(data,"fullGuid").isBlank()?fail(2,data,"fullAddress"):Optional.empty();
    }
    private static Optional<Failure> missing(JsonObject data,int step,String...keys){for(String key:keys)if(text(data,key).isBlank())return fail(step,data,key);return Optional.empty();}
    private static Optional<Failure> fail(int step,JsonObject data,String field){return Optional.of(new Failure(step,data,field));}
    private static boolean literalBoolean(JsonElement value){return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean();}
    private static boolean literalTrue(JsonElement value){return literalBoolean(value)&&value.getAsBoolean();}
    private static boolean identifiers(JsonElement value){
        if(value==null||!value.isJsonArray()||value.getAsJsonArray().isEmpty())return false;
        var unique=new HashSet<String>();
        for(var item:value.getAsJsonArray())if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString()||item.getAsString().isBlank()||!unique.add(item.getAsString()))return false;
        return true;
    }
    private static boolean checked(JsonElement value){
        if(value==null||!value.isJsonArray()||value.getAsJsonArray().isEmpty())return false;
        boolean selected=false;
        for(var item:value.getAsJsonArray()){
            if(!item.isJsonObject())return false;
            var choice=item.getAsJsonObject();var enabled=choice.get("checked");
            if(enabled==null||!enabled.isJsonPrimitive()||!enabled.getAsJsonPrimitive().isBoolean())return false;
            if(!enabled.getAsBoolean())continue;
            if(!string(choice.get("value"))||!string(choice.get("label")))return false;
            selected=true;
        }
        return selected;
    }
    private static boolean string(JsonElement value){return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&!value.getAsString().isBlank();}
    private static boolean email(String value){return value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");}
    private static JsonObject object(JsonObject parent,String key){var value=parent.get(key);return value!=null&&value.isJsonObject()?value.getAsJsonObject():new JsonObject();}
    private static String text(JsonObject data,String key){return NationalCatalogGs1Client.text(data,key);}
}
