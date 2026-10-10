package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.*;
import java.io.IOException;
import java.util.*;

/** User-owned mail transport, with durable one-send checkpoints and local unread history. */
public final class Gs1LettersService {
    public enum Kind { RENEWAL, MORE_CODES, CATALOG_ISSUE }
    @FunctionalInterface public interface Sender {void send(Gs1MailAccount account,Gs1MailClient.Letter letter)throws IOException;}
    private final Gs1Repository repository;
    private final Sender sender;
    public Gs1LettersService(Gs1Repository repository){this(repository,new Gs1MailClient()::send);}
    public Gs1LettersService(Gs1Repository repository,Sender sender){this.repository=Objects.requireNonNull(repository);this.sender=Objects.requireNonNull(sender);}
    public Gs1Repository.Request draft(String inn,String legalName,Kind kind,Integer requested,String comment){
        Gs1Membership.requireInn(inn);
        if(legalName==null||legalName.isBlank()||legalName.length()>500)throw new IllegalArgumentException("Legal enterprise name is required");
        if(kind==Kind.MORE_CODES&&(requested==null||requested<1||requested>1000000))throw new IllegalArgumentException("Enter a GTIN quantity between 1 and 1000000");
        String subject=switch(kind){case RENEWAL->"Продление членства в ГС1 РУС";case MORE_CODES->"Дополнительные коды GTIN";case CATALOG_ISSUE->"Ошибка Национального каталога";};
        String body="Здравствуйте!\n\nОрганизация: "+legalName.strip()+"\nИНН: "+inn+"\n\n"+switch(kind){
            case RENEWAL->"Просим сообщить порядок продления членства и выставить счет на ежегодный взнос.";
            case MORE_CODES->"Просим предоставить дополнительные коды GTIN в количестве "+requested+" и сообщить условия предоставления.";
            case CATALOG_ISSUE->"Просим помочь проверить ошибку при работе с Национальным каталогом.";
        }+"\n\n"+Objects.requireNonNullElse(comment,"").strip()+"\n\nС уважением,\n"+legalName.strip();
        String recipient=kind==Kind.RENEWAL?"mail@gs1ru.org":"markirovka@gs1ru.org";
        return repository.createRequest(inn,kind.name(),recipient,subject,body);
    }
    public void send(Gs1MailAccount account,String inn,String id)throws IOException{
        var request=repository.request(inn,id);
        String parent=parent(request);
        if(!parent.equals(id)&&!repository.hasConfirmedInvoice(inn,parent))throw new IllegalStateException("Confirm the invoice in this request before sending payment proof");
        var letter=letter(request);
        if(!repository.claimSend(inn,id))throw new IllegalStateException("Delivery must be checked before another send");
        try{
            sender.send(account,letter);
            repository.setState(inn,id,Gs1Repository.State.SENT);
            repository.addMessage(inn,parent,letter.messageId(),true,account.from(),request.body(),false);
            for(var attachment:letter.attachments())repository.addAttachment(inn,parent,letter.messageId(),attachment);
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
    public Gs1Repository.Request preparePaymentProof(String inn,String parentId,Gs1MailClient.Attachment proof){
        var original=repository.request(inn,parentId);
        if(!canPreparePaymentProof(inn,parentId))throw new IllegalStateException("Verify this invoice and reconcile any previous payment proof before preparing another send");
        var invoice=repository.verifiedInvoice(inn,parentId).orElseThrow();
        var request=repository.createRequest(inn,"PAYMENT_PROOF:"+parentId,original.recipient(),"Подтверждение оплаты — "+original.subject(),"Здравствуйте!\n\nИНН: "+inn+"\nСчет: "+invoice.number()+"\nСумма: "+invoice.amount()+" "+invoice.currency()+"\nНаправляем подтверждение оплаты по счету в данном обращении. Просим подтвердить получение.\n");
        repository.addAttachment(inn,request.id(),"draft",proof);return request;
    }
    public boolean canPreparePaymentProof(String inn,String parentId){
        var original=repository.request(inn,parentId);
        return !original.kind().equals("JOIN")&&!original.kind().startsWith("PAYMENT_PROOF:")&&repository.hasConfirmedInvoice(inn,parentId)&&!repository.hasBlockingOperation(inn,"PAYMENT_PROOF:"+parentId);
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
        Set<String> ids=new LinkedHashSet<>();repository.requests(inn).stream().filter(r->!r.kind().equals("JOIN")&&!r.kind().startsWith("PAYMENT_PROOF:")).forEach(r->ids.add(r.id()));
        for(var reply:new Gs1MailClient().read(account,inn,ids)){
            repository.addMessage(inn,reply.requestId(),reply.id(),false,reply.sender(),reply.body(),true);
            for(var attachment:reply.attachments())repository.addAttachment(inn,reply.requestId(),reply.id(),attachment);
        }
        return repository.unread(inn);
    }
    private static String parent(Gs1Repository.Request request){return request.kind().startsWith("PAYMENT_PROOF:")?request.kind().substring("PAYMENT_PROOF:".length()):request.id();}
}
