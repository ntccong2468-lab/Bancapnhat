package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.integration.gs1.Gs1Membership;
import java.util.*;

/** User-approved contact data and the two GS1 prefix capacities shown in the request dialog. */
public final class Gs1PrefixRequestModels {
    private Gs1PrefixRequestModels() {}
    public enum Quantity {
        STANDARD(999), EXTENDED(99999);
        private final int value;
        Quantity(int value){this.value=value;}
        public int value(){return value;}
        public static Quantity fromValue(int value){for(var quantity:values())if(quantity.value==value)return quantity;throw new IllegalArgumentException("gs1.prefix.invalid_quantity");}
    }
    public record ProfileDefaults(String legalName,String inn,List<String> gcps,String email,String phone,String signerName){
        public ProfileDefaults{gcps=List.copyOf(gcps);}
        @Override public String toString(){return "Gs1PrefixDefaults[redacted]";}
    }
    public record Profile(String legalName,String inn,List<String> gcps,String email,String phone,String signerName){
        public Profile{
            legalName=text(legalName,500);Gs1Membership.requireInn(inn);gcps=List.copyOf(gcps);
            if(gcps.isEmpty()||gcps.size()>100||new HashSet<>(gcps).size()!=gcps.size()||gcps.stream().anyMatch(p->!p.matches("[0-9]{4,12}")))throw new IllegalArgumentException("gs1.prefix.invalid_profile");
            email=text(email,254);phone=text(phone,80);signerName=text(signerName,200);
            if(!email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}")
                    ||!phone.matches("[+0-9() .-]+")||phone.replaceAll("[^0-9]","").length()<7||phone.replaceAll("[^0-9]","").length()>20)
                throw new IllegalArgumentException("gs1.prefix.invalid_profile");
        }
        @Override public String toString(){return "Gs1PrefixProfile[redacted]";}
    }
    public record RequestDraft(Profile profile,Quantity quantity,String comment,Gs1MailClient.Attachment reviewedDocument){
        public RequestDraft{
            Objects.requireNonNull(profile);Objects.requireNonNull(quantity);Objects.requireNonNull(reviewedDocument);
            comment=Objects.requireNonNullElse(comment,"").strip();
            if(comment.length()>20000)throw new IllegalArgumentException("gs1.prefix.invalid_profile");
        }
        @Override public String toString(){return "Gs1PrefixRequestDraft[redacted]";}
    }
    private static String text(String value,int limit){
        if(value==null||value.isBlank()||value.length()>limit||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("gs1.prefix.invalid_profile");return value.strip();
    }
}
