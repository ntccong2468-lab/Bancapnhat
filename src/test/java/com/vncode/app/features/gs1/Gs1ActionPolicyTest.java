package com.vncode.app.features.gs1;

import com.google.gson.JsonObject;
import com.vncode.app.integration.gs1.Gs1Membership;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.vncode.app.features.gs1.Gs1ActionPolicy.JoinAction.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1ActionPolicyTest {
    private static final String INN="7707083893";
    private static final Instant NOW=Instant.parse("2026-10-12T12:00:00Z");
    private static final LocalDate TODAY=LocalDate.of(2026,10,12);
    private static final GtinAvailability ZERO=new GtinAvailability(OptionalLong.of(0),true,1,0);
    private Gs1Membership member(Boolean active,boolean excluded,LocalDate expiry){return new Gs1Membership(INN,"Fixture",active,excluded,expiry,List.of(),NOW,Gs1Membership.CATALOG_SOURCE);}
    private JsonObject form(boolean sent){var f=new JsonObject();var applicant=new JsonObject();applicant.addProperty("inn",INN);f.add("applicant",applicant);f.addProperty("isSent",sent);return f;}
    private Gs1Repository.Request request(String kind,Gs1Repository.State state){return new Gs1Repository.Request(UUID.randomUUID().toString(),INN,kind,"mail@gs1ru.org","Fixture","Fixture",state,NOW);}
    private Gs1ActionPolicy.Decision decision(Gs1Membership member,JsonObject form,List<Gs1Repository.Request> requests,GtinAvailability available){return Gs1ActionPolicy.evaluate(member,form,requests,available,TODAY,NOW);}

    @Test void submittedNonMemberGetsInvoiceInquiryInsteadOfAdditionalPrefix(){
        var d=decision(member(false,false,null),form(true),List.of(),ZERO);
        assertEquals(APPLICATION_SUBMITTED,d.joinAction());assertTrue(d.joiningInvoice());assertFalse(d.moreCodes());assertFalse(d.restoration());
    }
    @Test void submittedRegistrationWithNoConfirmedMembershipExpiryCanInquireAboutTheJoiningInvoice(){
        var unconfirmed=member(true,false,null);
        var submitted=decision(unconfirmed,form(true),List.of(),ZERO);
        assertEquals(APPLICATION_SUBMITTED,submitted.joinAction());assertTrue(submitted.joiningInvoice());assertFalse(submitted.moreCodes());
        assertEquals(CHECK_STATUS,decision(unconfirmed,form(false),List.of(),ZERO).joinAction(),"Unknown membership must not become another joining submission");
        assertEquals(RECONCILE_DELIVERY,decision(unconfirmed,form(true),List.of(request("JOIN",Gs1Repository.State.SENDING)),ZERO).joinAction());
    }
    @Test void restorationIgnoresOldSubmittedApplicationButKeepsItsOwnDeliveryCheckpoint(){
        var excluded=member(false,true,null);var old=request("JOIN",Gs1Repository.State.SUBMITTED);
        var d=decision(excluded,form(true),List.of(old),ZERO);
        assertEquals(RESTORE_MEMBERSHIP,d.joinAction());assertTrue(d.restoration());
        for(var state:List.of(Gs1Repository.State.SENDING,Gs1Repository.State.RECONCILE_REQUIRED)){
            var blocked=decision(excluded,form(true),List.of(old,request("RESTORE_MEMBERSHIP",state)),ZERO);
            assertEquals(RECONCILE_DELIVERY,blocked.joinAction());assertFalse(blocked.restoration());
        }
        assertEquals(RESTORATION_REQUESTED,decision(excluded,form(true),List.of(old,request("RESTORE_MEMBERSHIP",Gs1Repository.State.SENT)),ZERO).joinAction());
    }
    @Test void zeroPartialAllowanceAndAnotherPrefixWithCodesDoNotOfferMoreCodes(){
        var active=member(true,false,TODAY.plusDays(120));
        assertTrue(decision(active,null,List.of(),ZERO).moreCodes());
        assertFalse(decision(active,null,List.of(),new GtinAvailability(OptionalLong.of(0),false,2,1)).moreCodes());
        assertFalse(decision(active,null,List.of(),new GtinAvailability(OptionalLong.of(100),true,2,0)).moreCodes());
        assertFalse(decision(active,null,List.of(),new GtinAvailability(OptionalLong.empty(),false,0,0)).moreCodes());
    }
    @Test void renewalOnlyAppearsWhenDueAndExpiredDoesNotRequestMoreCodes(){
        assertFalse(decision(member(true,false,TODAY.plusDays(61)),null,List.of(),ZERO).renewal());
        assertTrue(decision(member(true,false,TODAY.plusDays(60)),null,List.of(),ZERO).renewal());
        var expired=decision(member(true,false,TODAY.minusDays(1)),null,List.of(),ZERO);
        assertTrue(expired.renewal());assertFalse(expired.moreCodes());
    }
    @Test void missingStaleAndForeignApplicationFactsRequireFreshCheck(){
        assertEquals(CHECK_STATUS,decision(null,null,List.of(),ZERO).joinAction());
        var stale=new Gs1Membership(INN,"Fixture",false,false,null,List.of(),NOW.minus(Duration.ofDays(2)),Gs1Membership.CATALOG_SOURCE);
        assertEquals(CHECK_STATUS,decision(stale,form(false),List.of(),ZERO).joinAction());
        var foreign=form(false);foreign.getAsJsonObject("applicant").addProperty("inn","7811089030");
        assertEquals(CHECK_STATUS,decision(member(false,false,null),foreign,List.of(),ZERO).joinAction());
        var malformed=form(false);malformed.addProperty("isSent","false");
        assertEquals(CHECK_STATUS,decision(member(false,false,null),malformed,List.of(),ZERO).joinAction());
    }
    @Test void rejectedApplicationOverridesRemoteSentButLocalSubmittedMustBeReconciled(){
        var rejected=form(true);rejected.addProperty("errorText","fixture rejection");
        assertEquals(JOIN,decision(member(false,false,null),rejected,List.of(),ZERO).joinAction());
        assertEquals(RECONCILE_DELIVERY,decision(member(false,false,null),rejected,List.of(request("JOIN",Gs1Repository.State.SUBMITTED)),ZERO).joinAction());
        assertEquals(RECONCILE_DELIVERY,decision(member(false,false,null),form(false),List.of(request("JOIN",Gs1Repository.State.RECONCILE_REQUIRED)),ZERO).joinAction());
    }
    @Test void successfulOrUncertainInvoiceRequestCannotBePreparedAgain(){
        for(var state:List.of(Gs1Repository.State.SENDING,Gs1Repository.State.SENT,Gs1Repository.State.RECONCILE_REQUIRED))
            assertFalse(decision(member(false,false,null),form(true),List.of(request("JOINING_INVOICE",state)),ZERO).joiningInvoice());
    }
    @Test void authenticatedNewApplicationAllowsEditorButNeverBypassesLocalSubmissionCheckpoint(){
        var blank=form(false);blank.remove("isSent");var nonmember=member(false,false,null);
        assertEquals(CHECK_STATUS,decision(nonmember,blank,List.of(),ZERO).joinAction());
        assertEquals(JOIN,Gs1ActionPolicy.evaluate(nonmember,blank,List.of(),ZERO,TODAY,NOW,true).joinAction());
        for(var state:List.of(Gs1Repository.State.SENDING,Gs1Repository.State.RECONCILE_REQUIRED,Gs1Repository.State.SUBMITTED))
            assertEquals(RECONCILE_DELIVERY,Gs1ActionPolicy.evaluate(nonmember,blank,List.of(request("JOIN",state)),ZERO,TODAY,NOW,true).joinAction());
    }
}
