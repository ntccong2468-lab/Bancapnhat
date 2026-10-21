package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.*;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import java.io.IOException;
import java.util.*;

/** User-owned mail transport, with durable one-send checkpoints and local unread history. */
public final class Gs1LettersService {
    public enum Kind { RENEWAL, MORE_CODES, CATALOG_ISSUE, JOINING_INVOICE, RESTORE_MEMBERSHIP }
    @FunctionalInterface public interface Sender {void send(Gs1MailAccount account,Gs1MailClient.Letter letter)throws IOException;}
    private final Gs1Repository repository;
    private final Sender sender;
    public Gs1LettersService(Gs1Repository repository){this(repository,new Gs1MailClient()::send);}
    public Gs1LettersService(Gs1Repository repository,Sender sender){this.repository=Objects.requireNonNull(repository);this.sender=Objects.requireNonNull(sender);}
    public Gs1Repository.Request draft(String inn,String legalName,Kind kind,Integer requested,String comment){
        return draft(inn,legalName,kind,requested,comment,List.of());
    }
    public Gs1Repository.Request draft(String inn,String legalName,Kind kind,Integer requested,String comment,List<Gs1MailClient.Attachment> attachments){
        LicenseRuntime.require(Permission.GS1);
        attachments=List.copyOf(attachments);
        Gs1Membership.requireInn(inn);
        Objects.requireNonNull(kind);
        if(legalName==null||legalName.isBlank()||legalName.length()>500)throw new IllegalArgumentException("Legal enterprise name is required");
        if(comment!=null&&comment.length()>20000)throw new IllegalArgumentException("GS1 comment is too long");
        if(kind==Kind.MORE_CODES){if(requested==null)throw new IllegalArgumentException("gs1.prefix.invalid_quantity");Gs1PrefixRequestModels.Quantity.fromValue(requested);}
        if(Set.of(Kind.JOINING_INVOICE,Kind.RESTORE_MEMBERSHIP).contains(kind)&&repository.hasBlockingOperation(inn,kind.name()))throw new IllegalStateException("Check the previous GS1 request before preparing another letter");
        String subject=switch(kind){case RENEWAL->"Продление членства в ГС1 РУС";case MORE_CODES->"Дополнительные коды GTIN";case CATALOG_ISSUE->"Ошибка Национального каталога";case JOINING_INVOICE->"Счет на вступительный взнос в ГС1 РУС";case RESTORE_MEMBERSHIP->"Восстановление членства в ГС1 РУС";};
        String body="Здравствуйте!\n\nОрганизация: "+legalName.strip()+"\nИНН: "+inn+"\n\n"+switch(kind){
            case RENEWAL->"Просим сообщить порядок продления членства и выставить счет на ежегодный взнос.";
            case MORE_CODES->"Просим предоставить дополнительные коды GTIN в количестве "+requested+" и сообщить условия предоставления.";
            case CATALOG_ISSUE->"Просим помочь проверить ошибку при работе с Национальным каталогом.";
            case JOINING_INVOICE->"Просим проверить статус ранее поданной заявки на вступление в Ассоциацию ГС1 РУС и направить счет на вступительный взнос, если оплата еще требуется. Просим сообщить, если счет уже был оплачен или необходимы дополнительные документы.";
            case RESTORE_MEMBERSHIP->"Просим сообщить порядок восстановления членства нашей организации в Ассоциации ГС1 РУС и направить необходимые документы и счет. Ранее поданная заявка на вступление не является новым запросом на восстановление.";
        }+"\n\n"+Objects.requireNonNullElse(comment,"").strip()+"\n\nС уважением,\n"+legalName.strip();
        String recipient=kind==Kind.CATALOG_ISSUE?"markirovka@gs1ru.org":"mail@gs1ru.org";
        LicenseRuntime.require(Permission.GS1);
        return repository.createRequestWithAttachments(inn,kind.name(),recipient,subject,body,attachments);
    }
    public void send(Gs1MailAccount account,String inn,String id)throws IOException{
        var request=repository.request(inn,id);
        if(request.kind().equals(Kind.MORE_CODES.name()))throw new IllegalStateException("gs1.prefix.review_required");
        String parent=parent(request);
        if(request.kind().startsWith("PAYMENT_PROOF:")&&!repository.hasConfirmedInvoice(inn,parent))throw new IllegalStateException("Confirm the invoice in this request before sending payment proof");
        if(request.kind().startsWith("REMINDER:")){
            var original=repository.request(inn,parent);
            if(original.state()!=Gs1Repository.State.SENT||repository.messages(inn,parent).stream().anyMatch(m->!m.outgoing()))throw new IllegalStateException("Check the original request and its replies before sending a reminder");
        }
        var letter=letter(request);
        LicenseRuntime.require(Permission.GS1);
        if(!repository.claimSend(inn,id))throw new IllegalStateException("Delivery must be checked before another send");
        try{
            LicenseRuntime.require(Permission.GS1);
            sender.send(account,letter);
            repository.setState(inn,id,Gs1Repository.State.SENT);
            repository.addMessage(inn,parent,letter.messageId(),true,account.from(),request.body(),false);
            for(var attachment:letter.attachments())repository.addAttachment(inn,parent,letter.messageId(),attachment);
        }catch(LicenseDeniedException denial){
            repository.releaseUnsent(inn,id);
            throw denial;
        }catch(Gs1MailClient.NotSentException notSent){
            repository.releaseUnsent(inn,id);
            throw new IOException("gs1.mail_not_sent");
        }catch(IOException|RuntimeException ambiguous){
            repository.setState(inn,id,Gs1Repository.State.RECONCILE_REQUIRED);
            throw new IOException("GS1 mail delivery is unconfirmed; inspect the mailbox before sending again");
        }
    }
    public Gs1MailClient.Letter letter(Gs1Repository.Request request){
        return new Gs1MailClient.Letter(request.id(),parent(request),request.recipient(),request.subject(),request.body(),repository.attachments(request.inn(),request.id(),"draft"));
    }
    public Gs1ReminderPolicy.Result reminderStatus(String inn,String parentId,java.time.Instant now,boolean optedIn){
        var original=repository.request(inn,parentId);
        return Gs1ReminderPolicy.evaluate(original,repository.messages(inn,parentId),repository.requests(inn),now,optedIn);
    }
    /** Prepares one correlated reminder after an explicit opt-in; never sends it automatically. */
    public Gs1Repository.Request prepareReminder(String inn,String parentId,java.time.Instant now,boolean optedIn){
        LicenseRuntime.require(Permission.GS1);
        var original=repository.request(inn,parentId);
        if(reminderStatus(inn,parentId,now,optedIn)!=Gs1ReminderPolicy.Result.READY)throw new IllegalStateException("GS1 reminder is not ready; check delivery, mailbox replies and the waiting period");
        LicenseRuntime.require(Permission.GS1);
        return repository.createReminder(inn,parentId,"Напоминание — "+original.subject(),"Здравствуйте!\n\nНапоминаем о ранее направленном обращении по дополнительным кодам GTIN. Просим сообщить статус рассмотрения.\n\n"+original.body()).orElseThrow(()->new IllegalStateException("A reminder for this request was already prepared"));
    }
    public Gs1Repository.Request preparePaymentProof(String inn,String parentId,Gs1MailClient.Attachment proof){
        LicenseRuntime.require(Permission.GS1);
        var original=repository.request(inn,parentId);
        if(!canPreparePaymentProof(inn,parentId))throw new IllegalStateException("Verify this invoice and reconcile any previous payment proof before preparing another send");
        var invoice=repository.verifiedInvoice(inn,parentId).orElseThrow();
        LicenseRuntime.require(Permission.GS1);
        var request=repository.createRequest(inn,"PAYMENT_PROOF:"+parentId,original.recipient(),"Подтверждение оплаты — "+original.subject(),"Здравствуйте!\n\nИНН: "+inn+"\nСчет: "+invoice.number()+"\nСумма: "+invoice.amount()+" "+invoice.currency()+"\nНаправляем подтверждение оплаты по счету в данном обращении. Просим подтвердить получение.\n");
        repository.addAttachment(inn,request.id(),"draft",proof);return request;
    }
    public boolean canPreparePaymentProof(String inn,String parentId){
        var original=repository.request(inn,parentId);
        return !original.kind().equals("JOIN")&&!child(original)&&repository.hasConfirmedInvoice(inn,parentId)&&!repository.hasBlockingOperation(inn,"PAYMENT_PROOF:"+parentId);
    }
    /** Operator supplies a copy from Sent. Exact matching proves identity, not SMTP status itself. */
    public Gs1Repository.State reconcileSent(Gs1MailAccount account,String inn,String id,byte[] evidence)throws IOException{
        var request=repository.request(inn,id);
        if(request.state()!=Gs1Repository.State.SENDING&&request.state()!=Gs1Repository.State.RECONCILE_REQUIRED)throw new IllegalStateException("Only an unconfirmed delivery can be reconciled");
        var letter=letter(request);
        if(!new Gs1MailClient().matchesSentEvidence(evidence,account,letter))throw new IllegalArgumentException("Sent copy does not match this reviewed letter");
        try{repository.recordSentCopy(inn,id,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(evidence)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        repository.setState(inn,id,Gs1Repository.State.SENT);
        repository.addMessage(inn,parent(request),letter.messageId(),true,account.from(),request.body(),false);
        for(var attachment:letter.attachments())repository.addAttachment(inn,parent(request),letter.messageId(),attachment);
        return Gs1Repository.State.SENT;
    }
    public int refresh(Gs1MailAccount account,String inn)throws IOException{
        Set<String> ids=new LinkedHashSet<>();repository.requests(inn).stream().filter(r->!r.kind().equals("JOIN")&&!child(r)).forEach(r->ids.add(r.id()));
        for(var reply:new Gs1MailClient().read(account,inn,ids)){
            repository.addMessage(inn,reply.requestId(),reply.id(),false,reply.sender(),reply.body(),true);
            for(var attachment:reply.attachments())repository.addAttachment(inn,reply.requestId(),reply.id(),attachment);
        }
        return repository.unread(inn);
    }
    private static boolean child(Gs1Repository.Request request){return request.kind().startsWith("PAYMENT_PROOF:")||request.kind().startsWith("REMINDER:");}
    private static String parent(Gs1Repository.Request request){return child(request)?request.kind().substring(request.kind().indexOf(':')+1):request.id();}
}
