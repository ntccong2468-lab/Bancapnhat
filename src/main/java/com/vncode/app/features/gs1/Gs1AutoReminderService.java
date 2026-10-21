package com.vncode.app.features.gs1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.gs1.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, persistent consent permits one correlated automatic follow-up, never delivery replay. */
public final class Gs1AutoReminderService {
    @FunctionalInterface public interface AccountProvider {Optional<Gs1MailAccount> account(String inn);}
    @FunctionalInterface public interface Sender {void send(Gs1MailAccount account,Gs1MailClient.Letter letter,Gs1MailClient.BeforeSend guard)throws IOException;}
    @FunctionalInterface public interface MailboxRefresher {void refresh(Gs1MailAccount account,String inn)throws IOException;}
    public enum State { DISABLED, WAITING, READY, DELIVERY_UNCONFIRMED, REPLY_RECEIVED, ACCOUNT_CHANGED, CONTENT_CHANGED, KNOWN_UNSENT, SENT, RECONCILE_REQUIRED }
    public record Status(State state,boolean enabled,Instant approvedAt,String reminderId) {}
    public record TickResult(int considered,int sent,int stopped,int failed) {}
    private final Gs1Repository repository;
    private final Gs1LettersService letters;
    private final Sender sender;
    private final MailboxRefresher refresher;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();

    public Gs1AutoReminderService(Gs1Repository repository){
        this(repository,new Gs1MailClient()::send,new Gs1LettersService(repository)::refresh,Clock.systemUTC());
    }
    public Gs1AutoReminderService(Gs1Repository repository,Sender sender,MailboxRefresher refresher,Clock clock){
        this.repository=Objects.requireNonNull(repository);this.letters=new Gs1LettersService(repository);
        this.sender=Objects.requireNonNull(sender);this.refresher=Objects.requireNonNull(refresher);this.clock=Objects.requireNonNull(clock);
    }
    public void enable(String inn,String parentId,Gs1MailAccount account,boolean confirmed){
        LicenseRuntime.require(Permission.GS1);
        enable(inn,parentId,account,previewReminder(inn,parentId),confirmed);
    }
    public void enable(String inn,String parentId,Gs1MailAccount account,Gs1MailClient.Letter reviewed,boolean confirmed){
        LicenseRuntime.require(Permission.GS1);
        if(!confirmed)throw new IllegalArgumentException("gs1.reminder_consent_required");
        Objects.requireNonNull(account);Objects.requireNonNull(reviewed);var original=repository.request(inn,parentId);
        if(!original.kind().equals(Gs1LettersService.Kind.MORE_CODES.name())||original.state()!=Gs1Repository.State.SENT)
            throw new IllegalStateException("gs1.reminder_unconfirmed");
        if(repository.messages(inn,parentId).stream().anyMatch(m->!m.outgoing())||repository.requests(inn).stream().anyMatch(r->r.kind().equals("REMINDER:"+parentId)))
            throw new IllegalStateException("gs1.reminder_already_handled");
        var expected=expectedReminder(original);
        if(!letterContent(reviewed).equals(letterContent(expected)))throw new IllegalStateException("gs1.reminder_preview_changed");
        String accountDigest=accountDigest(account),contentDigest=consentedContent(original,expected);
        LicenseRuntime.require(Permission.GS1);
        if(!repository.enableReminderConsent(inn,parentId,accountDigest,contentDigest,clock.instant()))throw new IllegalStateException("gs1.reminder_already_handled");
    }
    /** Read-only preview of the exact template and recipient bound by subsequent explicit consent. */
    public Gs1MailClient.Letter previewReminder(String inn,String parentId){return expectedReminder(repository.request(inn,parentId));}
    public void disable(String inn,String parentId){repository.disableReminderConsent(inn,parentId);}
    public Status status(String inn,String parentId,Gs1MailAccount account){
        var original=repository.request(inn,parentId);var consent=repository.reminderConsent(inn,parentId).orElse(null);
        if(consent==null)return new Status(State.DISABLED,false,null,null);
        if(!consent.enabled())return status(State.DISABLED,consent);
        if(consent.reminderId()!=null){
            var reminder=repository.request(inn,consent.reminderId());
            return status(switch(reminder.state()){
                case SENT,RESOLVED->State.SENT;
                case SENDING,RECONCILE_REQUIRED->State.RECONCILE_REQUIRED;
                default->State.KNOWN_UNSENT;
            },consent);
        }
        if(account==null||!accountDigest(account).equals(consent.accountDigest()))return status(State.ACCOUNT_CHANGED,consent);
        var policy=letters.reminderStatus(inn,parentId,clock.instant(),true);
        if(policy==Gs1ReminderPolicy.Result.READY&&!consentedContent(original,expectedReminder(original)).equals(consent.contentDigest()))return status(State.CONTENT_CHANGED,consent);
        return status(switch(policy){
            case READY->State.READY;
            case WAITING->State.WAITING;
            case REPLY_RECEIVED->State.REPLY_RECEIVED;
            case ALREADY_PREPARED->State.KNOWN_UNSENT;
            default->State.DELIVERY_UNCONFIRMED;
        },consent);
    }
    public TickResult runDue(){return runDue(repository::mailAccount);}
    public TickResult runDue(AccountProvider accounts){
        Objects.requireNonNull(accounts);
        if(!running.compareAndSet(false,true))return new TickResult(0,0,0,0);
        int considered=0,sent=0,stopped=0,failed=0,due=0;
        try{
            for(var consent:repository.pendingReminderConsents(100)){
                considered++;
                try{
                    repository.markReminderChecked(consent.inn(),consent.requestId());
                    var account=accounts.account(consent.inn()).orElse(null);
                    if(status(consent.inn(),consent.requestId(),account).state()!=State.READY){stopped++;continue;}
                    if(due>=20)break;due++;
                    LicenseRuntime.require(Permission.GS1);
                    // Read current replies before authorizing a new delivery. An IMAP failure never means no reply.
                    refresher.refresh(account,consent.inn());
                    LicenseRuntime.require(Permission.GS1);
                    if(status(consent.inn(),consent.requestId(),accounts.account(consent.inn()).orElse(null)).state()!=State.READY){stopped++;continue;}
                    var original=repository.request(consent.inn(),consent.requestId());var expected=expectedReminder(original);
                    var prepared=repository.prepareAutomaticReminder(consent.inn(),consent.requestId(),consent.accountDigest(),consent.contentDigest(),expected.subject(),expected.body());
                    if(prepared.isEmpty()){stopped++;continue;}
                    if(send(consent,prepared.get(),account,accounts))sent++;else stopped++;
                }catch(LicenseDeniedException denial){stopped++;break;}
                catch(IOException|RuntimeException blocked){failed++;}
            }
            return new TickResult(considered,sent,stopped,failed);
        }finally{running.set(false);}
    }
    private boolean send(Gs1Repository.ReminderConsent consent,Gs1Repository.Request reminder,Gs1MailAccount account,AccountProvider accounts)throws IOException{
        var letter=letters.letter(reminder);
        Gs1MailClient.BeforeSend guard=()->{
            LicenseRuntime.require(Permission.GS1);
            var current=repository.reminderConsent(consent.inn(),consent.requestId()).orElse(null);
            var owner=repository.request(consent.inn(),consent.requestId());
            var configured=accounts.account(consent.inn()).orElse(null);
            if(current==null||!current.enabled()||!reminder.id().equals(current.reminderId())
                    ||!current.accountDigest().equals(consent.accountDigest())||!current.contentDigest().equals(consent.contentDigest())
                    ||configured==null||!accountDigest(configured).equals(consent.accountDigest())
                    ||owner.state()!=Gs1Repository.State.SENT||!owner.kind().equals(Gs1LettersService.Kind.MORE_CODES.name())
                    ||repository.messages(consent.inn(),consent.requestId()).stream().anyMatch(m->!m.outgoing())
                    ||!consentedContent(owner,letters.letter(repository.request(consent.inn(),reminder.id()))).equals(consent.contentDigest()))
                throw new Gs1MailClient.NotSentException();
        };
        guard.check();
        if(!repository.claimAutomaticReminder(consent.inn(),consent.requestId(),reminder.id(),consent.accountDigest(),consent.contentDigest()))return false;
        try{
            guard.check();
            sender.send(account,letter,guard);
            repository.setState(consent.inn(),reminder.id(),Gs1Repository.State.SENT);
            repository.addMessage(consent.inn(),consent.requestId(),letter.messageId(),true,account.from(),reminder.body(),false);
            for(var attachment:letter.attachments())repository.addAttachment(consent.inn(),consent.requestId(),letter.messageId(),attachment);
            return true;
        }catch(LicenseDeniedException denial){repository.releaseUnsent(consent.inn(),reminder.id());throw denial;}
        catch(Gs1MailClient.NotSentException notSent){repository.releaseUnsent(consent.inn(),reminder.id());return false;}
        catch(IOException|RuntimeException uncertain){repository.setState(consent.inn(),reminder.id(),Gs1Repository.State.RECONCILE_REQUIRED);throw new IOException("gs1.reminder_delivery_unconfirmed");}
    }
    private Gs1MailClient.Letter expectedReminder(Gs1Repository.Request original){
        return new Gs1MailClient.Letter(original.id(),original.id(),original.recipient(),"Напоминание — "+original.subject(),
                "Здравствуйте!\n\nНапоминаем о ранее направленном обращении по дополнительным кодам GTIN. Просим сообщить статус рассмотрения.\n\n"+original.body(),List.of());
    }
    private String consentedContent(Gs1Repository.Request original,Gs1MailClient.Letter reminder){
        var data=new JsonObject();data.addProperty("version",1);data.addProperty("inn",original.inn());data.addProperty("parent",original.id());
        data.add("original",letterContent(letters.letter(original)));data.add("reminder",letterContent(reminder));return digest(data.toString());
    }
    private static JsonObject letterContent(Gs1MailClient.Letter letter){
        var value=new JsonObject();value.addProperty("parent",letter.threadId());value.addProperty("recipient",letter.recipient());
        value.addProperty("subject",letter.subject());value.addProperty("body",letter.body());var attachments=new JsonArray();
        letter.attachments().stream().sorted(Comparator.comparing(Gs1MailClient.Attachment::name)).forEach(a->{var item=new JsonObject();item.addProperty("name",a.name());item.addProperty("type",a.type());item.addProperty("digest",digest(a.bytes()));attachments.add(item);});
        value.add("attachments",attachments);return value;
    }
    private static String accountDigest(Gs1MailAccount account){
        var value=new JsonObject();value.addProperty("smtp",account.smtpHost().toLowerCase(Locale.ROOT));value.addProperty("smtpPort",account.smtpPort());
        value.addProperty("imap",account.imapHost().toLowerCase(Locale.ROOT));value.addProperty("imapPort",account.imapPort());
        value.addProperty("username",account.username());value.addProperty("from",account.from());return digest(value.toString());
    }
    private static String digest(String value){return digest(value.getBytes(StandardCharsets.UTF_8));}
    private static String digest(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 is unavailable");}}
    private static Status status(State state,Gs1Repository.ReminderConsent consent){return new Status(state,consent.enabled(),consent.approvedAt(),consent.reminderId());}
}
