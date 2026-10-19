package com.vncode.app.features.kiz.status;

import com.vncode.app.features.kiz.KizService;
import com.vncode.app.integration.listing.ListingIdentity;
import java.util.*;

/** A marketplace observation, never inferred from a local reservation or a successful PUT. */
public record Kn3Mark(String orderId,String productId,String exemplarId,String code,State state,String reason) {
    public enum State { ACCEPTED,REJECTED,PENDING,EMPTY,UNAVAILABLE }
    public Kn3Mark { Objects.requireNonNull(state);code=Objects.toString(code,"");reason=safe(reason);if(state==State.ACCEPTED&&code.isBlank())state=State.EMPTY; }
    public String fingerprint(){return fingerprint(code);}
    public static String fingerprint(String code){return ListingIdentity.hash(Objects.toString(KizService.scannerSafeCode(code),""));}
    public static String safe(String code){String value=Objects.toString(code,"").replaceAll("[^a-zA-Z0-9_,.-]","_");return value.substring(0,Math.min(200,value.length()));}
    public static State wbState(String decision,String value){
        if(value==null||value.isBlank())return State.EMPTY;
        return switch(Objects.toString(decision,"")){
            case "sgtinIntroduced","sgtinSoldB2B","filled","optional" -> State.ACCEPTED;
            case "sgtinInvalidFormat","sgtinNoGS","sgtinHasInvalidSymbols","sgtinHasNonLatinSymbols","sgtinInvalidPattern","sgtinNotFound","sgtinEmitted","sgtinApplied","sgtinWrittenOff","sgtinRetired","sgtinWithdrawn","sgtinDisaggregation","sgtinDisaggregated","sgtinAppliedNotPaid" -> State.REJECTED;
            default -> State.PENDING;
        };
    }
    public static State summary(Collection<Kn3Mark> marks){
        if(marks.isEmpty())return State.EMPTY;
        if(marks.stream().anyMatch(m->m.state()==State.REJECTED))return State.REJECTED;
        if(marks.stream().allMatch(m->m.state()==State.ACCEPTED))return State.ACCEPTED;
        if(marks.stream().allMatch(m->m.state()==State.EMPTY))return State.EMPTY;
        return marks.stream().anyMatch(m->m.state()==State.UNAVAILABLE)?State.UNAVAILABLE:State.PENDING;
    }
    @Override public String toString(){return "Kn3Mark[state="+state+", reason="+reason+"]";}
}
