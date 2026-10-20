package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.tnved.FashionTnvedCatalog;
import java.util.Locale;

/** Reject only an explicit contradiction recorded in the app's tariff reference. */
public final class RegistrationTariffRules {
    private RegistrationTariffRules(){}
    public static String genderProblem(String code,String gender){
        String value=gender==null?"":gender.toLowerCase(Locale.ROOT);
        String actual=value.contains("муж")?"Мужской":value.contains("жен")?"Женский":"";
        if(actual.isBlank())return "";
        return new FashionTnvedCatalog().entries().stream().filter(e->e.code().equals(code))
                .filter(e->!e.gender().equals("Унисекс")&&!e.gender().equals(actual))
                .map(e->"ZNACK_TNVED_GENDER_MISMATCH: TN VED "+code+" · "+e.name()+"; "+gender)
                .findFirst().orElse("");
    }
}
