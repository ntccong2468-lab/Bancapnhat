package com.vncode.app.features.gs1;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.vncode.app.features.gs1.Gs1ReminderPolicy.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1ReminderPolicyTest {
    private static final Instant SENT_AT=Instant.parse("2026-10-01T12:00:00Z");
    private Gs1Repository.Request request(String kind,Gs1Repository.State state){return new Gs1Repository.Request("original","7707083893",kind,"markirovka@gs1ru.org","Fixture","Fixture",state,SENT_AT.minus(Duration.ofDays(30)));}
    private Gs1Repository.Message sent(){return new Gs1Repository.Message("mail",true,"sender@fixture.ru","Fixture",false,false,SENT_AT);}
    @Test void waitingPeriodStartsAtConfirmedSendAndNeedsExplicitOptIn(){
        var r=request("MORE_CODES",Gs1Repository.State.SENT);
        assertEquals(NOT_OPTED_IN,Gs1ReminderPolicy.evaluate(r,List.of(sent()),List.of(r),SENT_AT.plus(Duration.ofDays(8)),false));
        assertEquals(WAITING,Gs1ReminderPolicy.evaluate(r,List.of(sent()),List.of(r),SENT_AT.plus(Duration.ofDays(6)),true));
        assertEquals(READY,Gs1ReminderPolicy.evaluate(r,List.of(sent()),List.of(r),SENT_AT.plus(Duration.ofDays(7)),true));
    }
    @Test void unknownDeliveryAnyInboundReplyOrExistingReminderStopsNewReminder(){
        var r=request("MORE_CODES",Gs1Repository.State.SENT);var later=SENT_AT.plus(Duration.ofDays(20));
        for(var state:List.of(Gs1Repository.State.SENDING,Gs1Repository.State.RECONCILE_REQUIRED,Gs1Repository.State.DRAFT))
            assertEquals(DELIVERY_UNCONFIRMED,Gs1ReminderPolicy.evaluate(request("MORE_CODES",state),List.of(sent()),List.of(),later,true));
        assertEquals(DELIVERY_UNCONFIRMED,Gs1ReminderPolicy.evaluate(r,List.of(),List.of(),later,true));
        var reply=new Gs1Repository.Message("reply",false,"unverified@fixture.ru","Reply",true,false,SENT_AT.plusSeconds(1));
        assertEquals(REPLY_RECEIVED,Gs1ReminderPolicy.evaluate(r,List.of(sent(),reply),List.of(),later,true));
        var reminder=new Gs1Repository.Request("reminder",r.inn(),"REMINDER:"+r.id(),r.recipient(),"Fixture","Fixture",Gs1Repository.State.DRAFT,SENT_AT);
        assertEquals(ALREADY_PREPARED,Gs1ReminderPolicy.evaluate(r,List.of(sent()),List.of(reminder),later,true));
    }
    @Test void unsupportedKindsNeverBecomeAutomaticFollowUps(){
        assertEquals(UNSUPPORTED_REQUEST,Gs1ReminderPolicy.evaluate(request("RENEWAL",Gs1Repository.State.SENT),List.of(sent()),List.of(),SENT_AT.plus(Duration.ofDays(20)),true));
    }
}
