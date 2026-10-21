package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.features.license.*;
import com.vncode.app.integration.gs1.*;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static com.vncode.app.features.gs1.Gs1PrefixRequestModels.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1PrefixRequestServiceTest {
    @TempDir Path directory;
    private static final String INN="7700000000";
    private static final Instant NOW=Instant.parse("2026-10-13T12:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final List<String> GCPS=List.of("4601000","4602000","4603000");
    private final LicenseService prior=LicenseRuntime.service();
    private LicenseService denied;
    private Gs1Repository repository(){var repository=new Gs1Repository(directory.resolve("fixture.db"));repository.saveMailAccount(INN,account());return repository;}
    private Gs1MailAccount account(){return new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"fixture","sender@fixture.test","dpapi:fixture");}
    private Profile profile(){return new Profile("Fixture company",INN,GCPS,"contact@fixture.test","+7 000 000-00-00","Fixture Signer");}
    private NationalCatalogGs1Client.AccountSnapshot source(Integer... remaining){
        var prefixes=new ArrayList<Gs1Membership.Prefix>();for(int i=0;i<remaining.length;i++)prefixes.add(new Gs1Membership.Prefix(GCPS.get(i),remaining[i],List.of()));
        var member=new Gs1Membership(INN,"Fixture company",true,false,LocalDate.of(2027,3,1),prefixes,NOW,Gs1Membership.CATALOG_SOURCE);
        return source(member);
    }
    private NationalCatalogGs1Client.AccountSnapshot source(Gs1Membership member){
        var form=JsonParser.parseString("{\"applicant\":{\"inn\":\""+member.inn()+"\",\"email\":\"contact@fixture.test\",\"phone\":\"+7 000 000-00-00\"}}").getAsJsonObject();
        return new NationalCatalogGs1Client.AccountSnapshot(member,form,false);
    }
    private Gs1MailClient.Attachment document(boolean blank)throws Exception{
        try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()){
            var page=new PDPage();pdf.addPage(page);
            if(!blank)try(var content=new PDPageContentStream(pdf,page)){
                content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),14);content.newLineAtOffset(50,700);content.showText("Reviewed fixture prefix application and signature");content.endText();
            }
            pdf.save(bytes);return new Gs1MailClient.Attachment("GS1-01.04.01.pdf","application/pdf",bytes.toByteArray());
        }
    }
    private RequestDraft draft()throws Exception{return new RequestDraft(profile(),Quantity.STANDARD,"Reviewed comment",document(false));}
    @AfterEach void restore(){LicenseRuntime.install(prior);if(denied!=null)denied.close();}

    @Test void twoCapacitiesAndAllSourcedGcpContactDefaultsArePreserved(){
        assertEquals(999,Quantity.STANDARD.value());assertEquals(99999,Quantity.EXTENDED.value());assertThrows(IllegalArgumentException.class,()->Quantity.fromValue(1000));
        var service=new Gs1PrefixRequestService(repository(),(a,l,g)->{},CLOCK);
        var certificate=new CryptoProCertificateInfo("fixture","A".repeat(40),"CN=Fixture Signer, O=Fixture company","Fixture issuer",INN,NOW.minusSeconds(100),NOW.plusSeconds(100),true,"fixture","");
        var defaults=service.defaults(source(0,0,0),certificate);
        assertEquals(GCPS,defaults.gcps());assertEquals("contact@fixture.test",defaults.email());assertEquals("+7 000 000-00-00",defaults.phone());assertEquals("Fixture Signer",defaults.signerName());
        assertFalse(defaults.toString().contains(INN));assertFalse(profile().toString().contains("contact"));
    }
    @Test void openingWithRemainingCodesOrUnknownExpiryDoesNotAuthorizeSending(){
        var service=new Gs1PrefixRequestService(repository(),(a,l,g)->{},CLOCK);var positive=source(0,10,0);
        assertTrue(Gs1ActionPolicy.canOpenPrefixRequest(positive.membership(),NOW,LocalDate.of(2026,10,13)));
        assertEquals(Gs1PrefixRequestService.Block.CODES_AVAILABLE,service.preflight(INN,positive,NOW).block());
        assertEquals(10,service.preflight(INN,positive,NOW).remaining().orElseThrow());
        assertEquals(Gs1PrefixRequestService.Block.UNKNOWN_QUOTA,service.preflight(INN,source(0,null,0),NOW).block());
        var unknown=new Gs1Membership(INN,"Fixture company",true,false,null,positive.membership().prefixes(),NOW,Gs1Membership.CATALOG_SOURCE);
        assertTrue(Gs1ActionPolicy.canOpenPrefixRequest(unknown,NOW,LocalDate.of(2026,10,13)));assertEquals(3,Gs1ActionPolicy.prefixesForDisplay(unknown,NOW,LocalDate.of(2026,10,13)).size());
        assertEquals(Gs1PrefixRequestService.Block.NOT_ACTIVE,service.preflight(INN,source(unknown),NOW).block());assertEquals(GCPS,service.defaults(source(unknown),null).gcps());
    }
    @Test void frozenPdfMailBodyAndReviewedMetadataSurviveReopenAndMatchPreview()throws Exception{
        var repo=repository();var service=new Gs1PrefixRequestService(repo,(a,l,g)->{},CLOCK);var reviewed=draft();var preview=service.preview(reviewed);
        assertTrue(repo.requests(INN).isEmpty());var saved=service.prepare(reviewed,source(0,0,0),true);
        var reopened=new Gs1Repository(directory.resolve("fixture.db"));var checkpoint=reopened.prefixRequestReview(INN,saved.id()).orElseThrow();
        assertEquals(999,checkpoint.quantity());assertEquals(GCPS,checkpoint.gcps());assertEquals("mail@gs1ru.org",saved.recipient());assertEquals(preview.body(),saved.body());
        assertArrayEquals(reviewed.reviewedDocument().bytes(),reopened.attachments(INN,saved.id(),"draft").getFirst().bytes());assertTrue(saved.body().contains("4601000, 4602000, 4603000"));
    }
    @Test void allPositivePartialStaleAndForeignSnapshotsBlockBeforeClaimOrSmtp()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->writes.incrementAndGet(),CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        for(var changed:List.of(source(0,5,0),source(0,null,0))){
            assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->changed,true));
        }
        var base=source(0,0,0).membership();
        var stale=new Gs1Membership(INN,base.legalName(),true,false,base.expiry(),base.prefixes(),NOW.minus(Duration.ofDays(2)),base.source());
        var foreign=new Gs1Membership("7800000000",base.legalName(),true,false,base.expiry(),base.prefixes(),NOW,base.source());
        assertEquals(Gs1PrefixRequestService.Block.STALE_STATUS,assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->source(stale),true)).preflight().block());
        assertEquals(Gs1PrefixRequestService.Block.FOREIGN_ENTERPRISE,assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->source(foreign),true)).preflight().block());
        assertEquals(0,writes.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,saved.id()).state());
    }
    @Test void quotaIsRecheckedImmediatelyBeforeSmtpAndKnownUnsentDraftRemainsReviewable()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var reads=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->{g.check();writes.incrementAndGet();},CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        assertEquals(Gs1PrefixRequestService.Block.CODES_AVAILABLE,assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->reads.incrementAndGet()==1?source(0,0,0):source(1,0,0),true)).preflight().block());
        assertEquals(2,reads.get());assertEquals(0,writes.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,saved.id()).state());
        service.send(account(),INN,saved.id(),()->source(0,0,0),true);assertEquals(1,writes.get());assertEquals(Gs1Repository.State.SENT,repo.request(INN,saved.id()).state());
    }
    @Test void aCachedSnapshotReturnedByFreshReaderIsRejectedEvenWhenItsQuotaIsZero()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->writes.incrementAndGet(),CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        var current=source(0,0,0).membership();var cached=new Gs1Membership(INN,current.legalName(),true,false,current.expiry(),current.prefixes(),NOW.minusSeconds(120),current.source());
        assertEquals(Gs1PrefixRequestService.Block.STALE_STATUS,assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->source(cached),true)).preflight().block());assertEquals(0,writes.get());
    }
    @Test void changedReviewedBodyCannotReachSmtp()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->writes.incrementAndGet(),CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("fixture.db"));var p=c.prepareStatement("UPDATE gs1_requests SET body='Unreviewed replacement' WHERE id=?")){p.setString(1,saved.id());p.executeUpdate();}
        assertEquals(Gs1PrefixRequestService.Block.REVIEW_CHANGED,assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->source(0,0,0),true)).preflight().block());assertEquals(0,writes.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,saved.id()).state());
    }
    @Test void uncertainDeliveryPreservesCheckpointAndCannotReplay()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->{g.check();writes.incrementAndGet();throw new IOException("fixture SMTP answer lost");},CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        assertThrows(IOException.class,()->service.send(account(),INN,saved.id(),()->source(0,0,0),true));assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repo.request(INN,saved.id()).state());
        assertThrows(IllegalStateException.class,()->service.send(account(),INN,saved.id(),()->source(0,0,0),true));assertEquals(1,writes.get());
    }
    @Test void explicitConfirmationAndGs1RightsPrecedeExternalReadsAndWrites()throws Exception{
        var repo=repository();var reads=new AtomicInteger();var writes=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->writes.incrementAndGet(),CLOCK);var reviewed=draft();
        assertThrows(IllegalArgumentException.class,()->service.prepare(reviewed,source(0,0,0),false));assertTrue(repo.requests(INN).isEmpty());var saved=service.prepare(reviewed,source(0,0,0),true);
        assertThrows(IllegalArgumentException.class,()->service.send(account(),INN,saved.id(),()->{reads.incrementAndGet();return source(0,0,0);},false));
        denied=LicenseDomainFixture.denied(directory.resolve("denied"));LicenseRuntime.install(denied);
        assertThrows(LicenseGate.LicenseDeniedException.class,()->service.send(account(),INN,saved.id(),()->{reads.incrementAndGet();return source(0,0,0);},true));assertEquals(0,reads.get());assertEquals(0,writes.get());
    }
    @Test void blankPdfIsRejectedAndPhotoConversionProducesAReviewedPdf()throws Exception{
        var service=new Gs1PrefixRequestService(repository(),(a,l,g)->{},CLOCK);
        assertThrows(IllegalArgumentException.class,()->service.importDocument(document(true).bytes(),"application/pdf"));
        var photo=new BufferedImage(200,300,BufferedImage.TYPE_INT_RGB);var graphics=photo.createGraphics();graphics.setColor(Color.WHITE);graphics.fillRect(0,0,200,300);graphics.setColor(Color.BLACK);graphics.fillRect(25,40,80,12);graphics.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(photo,"png",bytes);var converted=service.importDocument(bytes.toByteArray(),"image/png");assertEquals("application/pdf",converted.type());assertTrue(converted.bytes().length>1000);
    }
    @Test void checkpointFailureRollsBackReviewedLetterAndPdfTogether()throws Exception{
        var repo=repository();var service=new Gs1PrefixRequestService(repo,(a,l,g)->{},CLOCK);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("fixture.db"));var s=c.createStatement()){s.execute("CREATE TRIGGER fixture_review_failure BEFORE INSERT ON gs1_prefix_request_reviews BEGIN SELECT RAISE(ABORT,'fixture failure'); END");}
        assertThrows(IllegalStateException.class,()->service.prepare(draft(),source(0,0,0),true));assertTrue(repo.requests(INN).isEmpty());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("fixture.db"));var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM gs1_attachments")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void licenseLostInTheLastFreshReadRestoresKnownUnsentDraft()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var reads=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->{g.check();writes.incrementAndGet();},CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        denied=LicenseDomainFixture.denied(directory.resolve("denied"));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->service.send(account(),INN,saved.id(),()->{if(reads.incrementAndGet()==2)LicenseRuntime.install(denied);return source(0,0,0);},true));
        assertEquals(2,reads.get());assertEquals(0,writes.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,saved.id()).state());
    }
    @Test void pdfChangedAfterClaimCannotReachSmtp()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->{
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("fixture.db"));var p=c.prepareStatement("UPDATE gs1_attachments SET content=? WHERE inn=? AND request_id=? AND message_id='draft'")){
                p.setBytes(1,"%PDF-unreviewed replacement".getBytes(java.nio.charset.StandardCharsets.US_ASCII));p.setString(2,INN);p.setString(3,l.id());p.executeUpdate();
            }catch(SQLException failure){throw new IOException("fixture update failed");}
            g.check();writes.incrementAndGet();
        },CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        assertEquals(Gs1PrefixRequestService.Block.REVIEW_CHANGED,assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->source(0,0,0),true)).preflight().block());
        assertEquals(0,writes.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,saved.id()).state());
    }
    @Test void changedMailIdentityBeforeSmtpRequiresAnotherReviewedSender()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var service=new Gs1PrefixRequestService(repo,(a,l,g)->{
            var changed=new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"fixture","other@fixture.test","dpapi:fixture");repo.saveMailAccount(INN,changed);g.check();writes.incrementAndGet();
        },CLOCK);var saved=service.prepare(draft(),source(0,0,0),true);
        assertEquals(Gs1PrefixRequestService.Block.MAIL_ACCOUNT_CHANGED,assertThrows(Gs1PrefixRequestService.BlockedException.class,()->service.send(account(),INN,saved.id(),()->source(0,0,0),true)).preflight().block());
        assertEquals(0,writes.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,saved.id()).state());
    }
}
