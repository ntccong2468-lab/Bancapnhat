package com.vncode.app.ui.gs1;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import java.time.Instant;
import java.util.Objects;

/** Retain the authenticated Catalog form and fill only missing, exact certificate identity fields. */
final class Gs1JoinAutofill {
    private Gs1JoinAutofill() {}

    static JsonObject prepare(String expectedInn,NationalCatalogGs1Client.AccountSnapshot source,
                              CryptoProCertificateInfo certificate,Instant now) {
        Gs1Membership.requireInn(expectedInn);Objects.requireNonNull(source);Objects.requireNonNull(now);
        var member=source.membership();var result=source.form();
        if(!expectedInn.equals(member.inn())||!Gs1Membership.CATALOG_SOURCE.equals(member.source())
                ||member.stale(now)||member.checkedAt().isAfter(now.plusSeconds(60))
                ||!result.has("applicant")||!result.get("applicant").isJsonObject()
                ||!expectedInn.equals(NationalCatalogGs1Client.text(result.getAsJsonObject("applicant"),"inn")))
            throw new SecurityException("GS1 application source belongs to an unverified enterprise");
        if(!usable(certificate,expectedInn,now))return result;
        var applicant=result.getAsJsonObject("applicant");
        // Do not turn a short company name, a certificate CN, or free text into a legal company name.
        fillBlank(applicant,"companyName",certificate.organizationName());
        fillBlank(applicant,"ogrn",certificate.enterpriseRegistrationNumber());
        return result;
    }

    private static boolean usable(CryptoProCertificateInfo certificate,String inn,Instant now) {
        if(certificate==null||!inn.equals(certificate.inn())||!certificate.hasPrivateKey()
                ||certificate.selector()==null||certificate.selector().isBlank()
                ||certificate.thumbprint()==null||!certificate.thumbprint().replaceAll("\\s","").matches("[A-Fa-f0-9]{40}")
                ||certificate.validFrom()==null||certificate.validFrom().isAfter(now)
                ||certificate.validTo()==null||!certificate.validTo().isAfter(now))return false;
        String subjectInn=CryptoProCertificateInfo.enterpriseInn(certificate.subject());
        return subjectInn.isBlank()||inn.equals(subjectInn);
    }

    private static void fillBlank(JsonObject object,String field,String source) {
        if(source==null||source.isBlank()||source.length()>500||source.chars().anyMatch(Character::isISOControl))return;
        JsonElement current=object.get(field);
        if(current==null||current.isJsonNull()||current.isJsonPrimitive()&&current.getAsJsonPrimitive().isString()&&current.getAsString().isBlank())
            object.addProperty(field,source);
    }
}
