package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.*;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Gs1JoinAutofillTest {
    private static final String INN="7701234567";
    private static final Instant NOW=Instant.parse("2026-11-18T12:00:00Z");
    @Test void exactSameEnterpriseCertificateFillsOnlyMissingIdentityAndKeepsCatalogAndUnknownData(){
        var original=form(INN);var before=original.deepCopy();
        var prepared=Gs1JoinAutofill.prepare(INN,source(original,INN,NOW),certificate(INN,"O=Verified certificate company, OGRN=1234567890123, INNLE="+INN),NOW);
        var applicant=prepared.getAsJsonObject("applicant");assertEquals("Verified certificate company",applicant.get("companyName").getAsString());assertEquals("1234567890123",applicant.get("ogrn").getAsString());
        assertEquals(before,original);assertEquals("keep",prepared.getAsJsonObject("unknownServerData").get("value").getAsString());
        for(String key:List.of("bankDetails","addresses","companyDetails","contacts"))assertFalse(prepared.has(key),"Autofill cannot invent "+key);
        for(String key:List.of("companyNameShort","countryId","kpp","okved2","opf","position","email","phone","firstName","lastName","isMarked"))assertFalse(applicant.has(key),"A certificate identity is not evidence for "+key);
    }
    @Test void existingReviewedIdentityAndMalformedNonblankFieldsAreNeverOverwritten(){
        var original=form(INN);var applicant=original.getAsJsonObject("applicant");applicant.addProperty("companyName","Catalog company");applicant.addProperty("ogrn","9876543210987");
        var prepared=Gs1JoinAutofill.prepare(INN,source(original,INN,NOW),certificate(INN,"O=Different certificate display, OGRN=1234567890123"),NOW);assertEquals(original,prepared);
        applicant.add("companyName",new JsonArray());applicant.addProperty("ogrn",123);prepared=Gs1JoinAutofill.prepare(INN,source(original,INN,NOW),certificate(INN,"O=Certificate company, OGRN=1234567890123"),NOW);assertEquals(original,prepared);
    }
    @Test void foreignApplicantMembershipAndStaleOrFutureSourcesCannotAutofill(){
        for(var source:List.of(source(form("7801234567"),INN,NOW),source(form(INN),"7801234567",NOW),source(form(INN),INN,NOW.minusSeconds(86401)),source(form(INN),INN,NOW.plusSeconds(61))))
            assertThrows(SecurityException.class,()->Gs1JoinAutofill.prepare(INN,source,certificate(INN,"O=Certificate, OGRN=1234567890123"),NOW));
        var wrongSource=new Gs1Membership(INN,"",false,false,null,List.of(),NOW,"https://unverified.invalid");
        assertThrows(SecurityException.class,()->Gs1JoinAutofill.prepare(INN,new NationalCatalogGs1Client.AccountSnapshot(wrongSource,form(INN),true),null,NOW));
    }
    @Test void unusableOrForeignCertificateKeepsTheRemoteFormWithoutAddingGuessedValues(){
        var original=form(INN);String subject="O=Certificate company, OGRN=1234567890123";
        var certificates=List.of(certificate("7801234567",subject),certificate(INN,subject+", INNLE=7801234567"),
                cert(INN,subject,"",NOW.minusSeconds(1),NOW.plusSeconds(1),true,"A".repeat(40)),
                cert(INN,subject,"selector",NOW.minusSeconds(10),NOW,true,"A".repeat(40)),
                cert(INN,subject,"selector",NOW.plusSeconds(1),NOW.plusSeconds(10),true,"A".repeat(40)),
                cert(INN,subject,"selector",NOW.minusSeconds(1),NOW.plusSeconds(1),false,"A".repeat(40)),
                cert(INN,subject,"selector",NOW.minusSeconds(1),NOW.plusSeconds(1),true,"invalid"));
        for(var certificate:certificates)assertEquals(original,Gs1JoinAutofill.prepare(INN,source(original,INN,NOW),certificate,NOW));
        assertEquals(original,Gs1JoinAutofill.prepare(INN,source(original,INN,NOW),null,NOW));
    }
    @Test void legalEntityAndIndividualEntrepreneurUseOnlyTheirExactRegistrationAttribute(){
        assertEquals("1234567890123",certificate(INN,"OID.1.2.643.100.1=1234567890123, OGRNIP=123456789012345").enterpriseRegistrationNumber());
        String personal="770123456789";var source=source(form(personal),personal,NOW);
        var prepared=Gs1JoinAutofill.prepare(personal,source,certificate(personal,"O=Verified IP name, OID.1.2.643.100.5=123456789012345, OGRN=1234567890123"),NOW);
        assertEquals("123456789012345",prepared.getAsJsonObject("applicant").get("ogrn").getAsString());
        for(String subject:List.of("OGRNIP=123456789012345","OGRN=123456789012345","OGRN=1234567890123, ОГРН=9876543210987"))assertEquals("",certificate(INN,subject).enterpriseRegistrationNumber());
        var cnOnly=Gs1JoinAutofill.prepare(personal,source,certificate(personal,"CN=Private Person, OGRNIP=123456789012345"),NOW);assertFalse(cnOnly.getAsJsonObject("applicant").has("companyName"),"Do not invent an IP legal name from CN");
    }
    @Test void aCompleteCatalogProfileIsKeptExactAndNeedsNoCertificateInference(){
        var original=Gs1JoinFormValidationContractTest.validForm();var prepared=Gs1JoinAutofill.prepare(INN,source(original,INN,NOW),null,NOW);
        assertEquals(original,prepared);assertNotSame(original,prepared);assertTrue(Gs1JoinFormValidation.first(prepared,INN).isEmpty());
    }
    private static JsonObject form(String inn){var value=new JsonObject();var applicant=new JsonObject();applicant.addProperty("inn",inn);value.add("applicant",applicant);var unknown=new JsonObject();unknown.addProperty("value","keep");value.add("unknownServerData",unknown);return value;}
    private static NationalCatalogGs1Client.AccountSnapshot source(JsonObject form,String inn,Instant checkedAt){return new NationalCatalogGs1Client.AccountSnapshot(new Gs1Membership(inn,"",false,false,null,List.of(),checkedAt,Gs1Membership.CATALOG_SOURCE),form,true);}
    private static CryptoProCertificateInfo certificate(String inn,String subject){return cert(inn,subject,"selector",NOW.minusSeconds(100),NOW.plusSeconds(100),true,"A".repeat(40));}
    private static CryptoProCertificateInfo cert(String inn,String subject,String selector,Instant from,Instant until,boolean key,String thumbprint){return new CryptoProCertificateInfo(selector,thumbprint,subject,"Fixture issuer",inn,from,until,key,"Fixture provider","");}
}
