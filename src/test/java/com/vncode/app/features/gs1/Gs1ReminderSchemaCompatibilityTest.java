package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1ReminderSchemaCompatibilityTest {
    @TempDir Path directory;
    @Test void oldGs1TablesAndVerifiedSnapshotSurviveAdditiveReminderSetup()throws Exception{
        Path database=directory.resolve("old.db");var original=new Gs1Repository(database);String inn="7707083893";
        var request=original.createRequest(inn,"MORE_CODES","markirovka@gs1ru.org","Fixture","Reviewed body");original.setState(inn,request.id(),Gs1Repository.State.SENT);
        original.addMessage(inn,request.id(),"sent",true,"sender@fixture.ru","Reviewed body",false);
        original.addAttachment(inn,request.id(),"draft",new Gs1MailClient.Attachment("fixture.pdf","application/pdf","%PDF-fixture".getBytes()));
        var account=new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","sender@fixture.ru","dpapi:fixture");original.saveMailAccount(inn,account);
        // New tables are additive; all prior GS1 tables retain their actual schemas and state tokens.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()){
            s.execute("DROP TABLE gs1_auto_reminder_consents");s.execute("DROP TABLE gs1_auto_reminder_checks");s.execute("DROP TABLE gs1_membership_request_resolutions");s.execute("DROP TABLE gs1_prefix_request_reviews");
        }
        Path snapshot=directory.resolve("verified-old-snapshot.db");Files.copy(database,snapshot);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(database)),MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(snapshot)));
        assertIntegrity(snapshot);
        var upgraded=new Gs1Repository(database);
        assertEquals("Reviewed body",upgraded.request(inn,request.id()).body());assertEquals(Gs1Repository.State.SENT,upgraded.request(inn,request.id()).state());
        assertEquals(1,upgraded.messages(inn,request.id()).size());assertEquals(1,upgraded.attachments(inn,request.id(),"draft").size());assertEquals(account,upgraded.mailAccount(inn).orElseThrow());
        assertTrue(upgraded.reminderConsent(inn,request.id()).isEmpty());assertTrue(upgraded.pendingReminderConsents(100).isEmpty());
        assertIntegrity(database);assertIntegrity(snapshot);
        // Restoring the verified pre-upgrade copy recreates new tables and preserves the original journal.
        Path restored=directory.resolve("restored.db");Files.copy(snapshot,restored);var reopened=new Gs1Repository(restored);
        assertEquals("Reviewed body",reopened.request(inn,request.id()).body());assertTrue(reopened.pendingReminderConsents(100).isEmpty());assertIntegrity(restored);
    }
    @Test void revokedConsentCannotClaimPreparedReminder()throws Exception{
        var repository=new Gs1Repository(directory.resolve("claim.db"));var original=repository.createRequest("7707083893","MORE_CODES","markirovka@gs1ru.org","Fixture","Fixture");repository.setState(original.inn(),original.id(),Gs1Repository.State.SENT);
        String account="a".repeat(64),content="b".repeat(64);repository.enableReminderConsent(original.inn(),original.id(),account,content,java.time.Instant.now());
        var reminder=repository.prepareAutomaticReminder(original.inn(),original.id(),account,content,"Reminder","Fixture").orElseThrow();repository.disableReminderConsent(original.inn(),original.id());
        assertFalse(repository.claimAutomaticReminder(original.inn(),original.id(),reminder.id(),account,content));assertEquals(Gs1Repository.State.DRAFT,repository.request(original.inn(),reminder.id()).state());
    }
    private enum LegacyState {DRAFT,SENDING,SENT,SUBMITTED,RECONCILE_REQUIRED,REJECTED}
    @Test void membershipResolutionKeepsLegacyStateTokensAndAllowsLaterRestorationLifecycle()throws Exception{
        Path database=directory.resolve("legacy-state.db");var repository=new Gs1Repository(database);String inn="7707083893";
        var earlier=repository.createRequest(inn,"RESTORE_MEMBERSHIP","mail@gs1ru.org","Fixture","Fixture");repository.setState(inn,earlier.id(),Gs1Repository.State.SENT);
        var now=java.time.Instant.now();var date=java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));
        var member=new Gs1Membership(inn,"Fixture",true,false,date.plusDays(120),java.util.List.of(),now,Gs1Membership.CATALOG_SOURCE);
        assertEquals(1,repository.completeMembershipRequests(member,"production"));
        assertEquals(Gs1Repository.State.RESOLVED,repository.request(inn,earlier.id()).state());
        assertEquals(Gs1Repository.State.RESOLVED,repository.requests(inn).getFirst().state());
        assertThrows(IllegalArgumentException.class,()->repository.setState(inn,earlier.id(),Gs1Repository.State.RESOLVED));
        // A later exclusion can submit a new restoration, without resending the completed request.
        var later=repository.createRequest(inn,"RESTORE_MEMBERSHIP","mail@gs1ru.org","Later","Fixture");assertTrue(repository.claimSend(inn,later.id()));
        repository.setState(inn,later.id(),Gs1Repository.State.RECONCILE_REQUIRED);
        var uncertain=repository.createRequest(inn,"RESTORE_MEMBERSHIP","mail@gs1ru.org","Blocked","Fixture");assertFalse(repository.claimSend(inn,uncertain.id()));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var p=c.prepareStatement("SELECT state FROM gs1_requests WHERE id=?")){
            p.setString(1,earlier.id());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("SENT",r.getString(1));}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT state FROM gs1_requests")){
                while(r.next()){String stored=r.getString(1);assertDoesNotThrow(()->LegacyState.valueOf(stored));}
            }
        }
        assertIntegrity(database);
    }
    private void assertIntegrity(Path database)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()){
            try(var r=s.executeQuery("PRAGMA integrity_check")){assertTrue(r.next());assertEquals("ok",r.getString(1));assertFalse(r.next());}
            try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
        }
    }
}
