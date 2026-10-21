package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.gs1.*;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.vncode.app.features.gs1.Gs1PrefixRequestModels.*;

/** Reviewed prefix requests require an authoritative zero quota across every active prefix at SMTP send. */
public final class Gs1PrefixRequestService {
    public static final String RECIPIENT="mail@gs1ru.org";
    public enum Block { NONE, FOREIGN_ENTERPRISE, UNVERIFIED_SOURCE, STALE_STATUS, NOT_ACTIVE, UNKNOWN_QUOTA, CODES_AVAILABLE, REVIEW_SOURCE_CHANGED, REVIEW_REQUIRED, REVIEW_CHANGED, MAIL_ACCOUNT_CHANGED }
    public record Preflight(Block block,OptionalLong remaining){
        public boolean canSend(){return block==Block.NONE;}
        public String messageKey(){return "gs1.prefix."+block.name().toLowerCase(Locale.ROOT);}
    }
    public static final class BlockedException extends IllegalStateException {
        private final Preflight preflight;
        private BlockedException(Preflight preflight){super(preflight.messageKey());this.preflight=preflight;}
        public Preflight preflight(){return preflight;}
    }
    @FunctionalInterface public interface FreshSnapshotReader {NationalCatalogGs1Client.AccountSnapshot read()throws IOException;}
    @FunctionalInterface public interface Sender {void send(Gs1MailAccount account,Gs1MailClient.Letter letter,Gs1MailClient.BeforeSend guard)throws IOException;}
    private final Gs1Repository repository;
    private final Gs1LettersService letters;
    private final Gs1DashboardService dashboard;
    private final Sender sender;
    private final Clock clock;
    public Gs1PrefixRequestService(Gs1Repository repository){this(repository,new Gs1MailClient()::send,Clock.systemUTC());}
    public Gs1PrefixRequestService(Gs1Repository repository,Sender sender,Clock clock){
        this.repository=Objects.requireNonNull(repository);this.letters=new Gs1LettersService(repository);this.dashboard=new Gs1DashboardService(repository);
        this.sender=Objects.requireNonNull(sender);this.clock=Objects.requireNonNull(clock);
    }
    public ProfileDefaults defaults(NationalCatalogGs1Client.AccountSnapshot source,CryptoProCertificateInfo certificate){
        Objects.requireNonNull(source);var member=source.membership();var applicant=applicant(source.form());
        if(!member.inn().equals(text(applicant,"inn")))throw new SecurityException("GS1 prefix source belongs to another enterprise");
        if(certificate!=null&&!member.inn().equals(certificate.inn()))throw new SecurityException("GS1 prefix certificate belongs to another enterprise");
        String name=member.legalName();if(name.isBlank()&&certificate!=null)name=Objects.requireNonNullElse(certificate.ownerName(),"");
        String email=text(applicant,"email"),phone=text(applicant,"phone");
        var form=source.form();if(form.has("contacts")&&form.get("contacts").isJsonArray())for(var entry:form.getAsJsonArray("contacts"))if(entry.isJsonObject()){
            if(email.isBlank())email=text(entry.getAsJsonObject(),"email");if(phone.isBlank())phone=text(entry.getAsJsonObject(),"phone");
        }
        String signer=certificate==null?"":certificate.signerFullName();
        if(signer.isBlank())signer=(text(applicant,"lastName")+" "+text(applicant,"firstName")).strip();
        var now=clock.instant();var identifiers=Gs1ActionPolicy.prefixesForDisplay(member,now,now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate()).stream().map(Gs1Membership.Prefix::gcp).distinct().toList();
        return new ProfileDefaults(name,member.inn(),identifiers,email,phone,signer);
    }
    public Preflight preflight(String expectedInn,NationalCatalogGs1Client.AccountSnapshot source,Instant now){
        Gs1Membership.requireInn(expectedInn);Objects.requireNonNull(now);
        if(source==null)return blocked(Block.UNVERIFIED_SOURCE);
        var member=source.membership();
        if(!expectedInn.equals(member.inn())||!expectedInn.equals(text(applicant(source.form()),"inn")))return blocked(Block.FOREIGN_ENTERPRISE);
        if(!Gs1Membership.CATALOG_SOURCE.equals(member.source()))return blocked(Block.UNVERIFIED_SOURCE);
        if(member.stale(now)||member.checkedAt().isAfter(now.plusSeconds(60)))return blocked(Block.STALE_STATUS);
        var date=now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate();var status=member.status(date);
        if(status!=Gs1Membership.Status.ACTIVE&&status!=Gs1Membership.Status.EXPIRING)return blocked(Block.NOT_ACTIVE);
        var quota=dashboard.availability(member,date);
        if(!quota.complete()||quota.activePrefixes()==0||quota.available().isEmpty())return new Preflight(Block.UNKNOWN_QUOTA,quota.available());
        return new Preflight(quota.available().getAsLong()>0?Block.CODES_AVAILABLE:Block.NONE,quota.available());
    }
    /** Photo imports use the existing orientation-aware conversion; PDF bytes remain the reviewed evidence. */
    public Gs1MailClient.Attachment importDocument(byte[] bytes,String type)throws IOException{
        LicenseRuntime.require(Permission.GS1);
        var document=switch(Objects.requireNonNull(type)){
            case "image/png","image/jpeg"->new Gs1PrefixDocumentService().paperPhoto(bytes);
            case "application/pdf"->new Gs1MailClient.Attachment("GS1-01.04.01.pdf",type,bytes);
            default->throw new IllegalArgumentException("gs1.prefix.document_required");
        };
        validateDocument(document);return document;
    }
    public Gs1Repository.Request prepare(RequestDraft reviewed,NationalCatalogGs1Client.AccountSnapshot source,boolean confirmed)throws IOException{
        LicenseRuntime.require(Permission.GS1);Objects.requireNonNull(reviewed);
        if(!confirmed)throw new IllegalArgumentException("gs1.prefix.confirm_required");
        var profile=reviewed.profile();require(preflight(profile.inn(),source,clock.instant()));
        if(!new HashSet<>(profile.gcps()).equals(new HashSet<>(activeGcps(source.membership(),clock.instant()))))throw new BlockedException(blocked(Block.REVIEW_SOURCE_CHANGED));
        if(repository.hasBlockingOperation(profile.inn(),"MORE_CODES"))throw new IllegalStateException("gs1.prefix.delivery_unconfirmed");
        validateDocument(reviewed.reviewedDocument());
        var letter=preview(reviewed);String id=letter.id();
        String digest=digest(letter,profile.inn(),profile.gcps(),reviewed.quantity().value());
        var checkpoint=new Gs1Repository.PrefixRequestReview(profile.inn(),id,profile.gcps(),reviewed.quantity().value(),digest,source.membership().checkedAt());
        LicenseRuntime.require(Permission.GS1);return repository.createReviewedPrefixRequest(letter,checkpoint);
    }
    /** The dialog and persisted checkpoint share one exact mail template; preview never writes or sends. */
    public Gs1MailClient.Letter preview(RequestDraft reviewed){
        Objects.requireNonNull(reviewed);String id=UUID.randomUUID().toString();
        return new Gs1MailClient.Letter(id,id,RECIPIENT,"Заявление на дополнительный префикс ГС1 РУС",body(reviewed.profile(),reviewed.quantity(),reviewed.comment()),List.of(reviewed.reviewedDocument()));
    }
    public static String body(Profile profile,Quantity quantity,String comment){
        Objects.requireNonNull(profile);Objects.requireNonNull(quantity);comment=Objects.requireNonNullElse(comment,"").strip();
        if(comment.length()>20000)throw new IllegalArgumentException("gs1.prefix.invalid_profile");
        return "Здравствуйте!\n\nОрганизация: "+profile.legalName()+"\nИНН: "+profile.inn()+"\nКоды предприятия ГС1 РУС (GCP): "+String.join(", ",profile.gcps())+
                "\nE-mail: "+profile.email()+"\nТелефон: "+profile.phone()+"\nПодписант: "+profile.signerName()+
                "\n\nПросим предоставить дополнительный префикс с возможностью генерации "+quantity.value()+" кодов GTIN. Заявление по форме 01.04.01 прилагается.\n\n"+comment;
    }
    public void send(Gs1MailAccount account,String inn,String id,FreshSnapshotReader fresh,boolean confirmed)throws IOException{
        LicenseRuntime.require(Permission.GS1);Objects.requireNonNull(account);Objects.requireNonNull(fresh);
        if(!confirmed)throw new IllegalArgumentException("gs1.prefix.confirm_required");
        var request=repository.request(inn,id);var review=repository.prefixRequestReview(inn,id).orElseThrow(()->new BlockedException(blocked(Block.REVIEW_REQUIRED)));
        if(!request.kind().equals("MORE_CODES")||request.state()!=Gs1Repository.State.DRAFT)throw new IllegalStateException("gs1.prefix.delivery_unconfirmed");
        var letter=validatedLetter(request,review);requireAccount(inn,account);require(freshPreflight(inn,review,fresh));
        LicenseRuntime.require(Permission.GS1);
        if(!repository.claimSend(inn,id))throw new IllegalStateException("gs1.prefix.delivery_unconfirmed");
        var blocked=new AtomicReference<Preflight>();
        Gs1MailClient.BeforeSend guard=()->{
            LicenseRuntime.require(Permission.GS1);
            try{validatedLetter(repository.request(inn,id),review);requireAccount(inn,account);}catch(BlockedException changed){blocked.set(changed.preflight());throw new Gs1MailClient.NotSentException();}
            Preflight check;
            try{check=freshPreflight(inn,review,fresh);}catch(IOException unavailable){throw new Gs1MailClient.NotSentException();}
            if(!check.canSend()){blocked.set(check);throw new Gs1MailClient.NotSentException();}
            try{requireAccount(inn,account);}catch(BlockedException changed){blocked.set(changed.preflight());throw new Gs1MailClient.NotSentException();}
            LicenseRuntime.require(Permission.GS1);
        };
        try{
            LicenseRuntime.require(Permission.GS1);sender.send(account,letter,guard);
            repository.setState(inn,id,Gs1Repository.State.SENT);
            repository.addMessage(inn,id,letter.messageId(),true,account.from(),request.body(),false);
            for(var attachment:letter.attachments())repository.addAttachment(inn,id,letter.messageId(),attachment);
        }catch(LicenseDeniedException denial){repository.releaseUnsent(inn,id);throw denial;}
        catch(Gs1MailClient.NotSentException notSent){repository.releaseUnsent(inn,id);if(blocked.get()!=null)throw new BlockedException(blocked.get());throw new IOException("gs1.mail_not_sent");}
        catch(IOException|RuntimeException uncertain){repository.setState(inn,id,Gs1Repository.State.RECONCILE_REQUIRED);throw new IOException("gs1.prefix.delivery_unconfirmed");}
    }
    private Preflight freshPreflight(String inn,Gs1Repository.PrefixRequestReview review,FreshSnapshotReader fresh)throws IOException{
        LicenseRuntime.require(Permission.GS1);Instant started=clock.instant();var source=fresh.read();var now=clock.instant();
        var check=preflight(inn,source,now);if(!check.canSend())return check;
        if(source.membership().checkedAt().isBefore(started.minusSeconds(30)))return blocked(Block.STALE_STATUS);
        if(!new HashSet<>(review.gcps()).equals(new HashSet<>(activeGcps(source.membership(),now))))return blocked(Block.REVIEW_SOURCE_CHANGED);
        return check;
    }
    private void requireAccount(String inn,Gs1MailAccount reviewed){
        var current=repository.mailAccount(inn).orElse(null);
        if(current==null||!current.smtpHost().equalsIgnoreCase(reviewed.smtpHost())||current.smtpPort()!=reviewed.smtpPort()
                ||!current.imapHost().equalsIgnoreCase(reviewed.imapHost())||current.imapPort()!=reviewed.imapPort()
                ||!current.username().equals(reviewed.username())||!current.from().equals(reviewed.from()))throw new BlockedException(blocked(Block.MAIL_ACCOUNT_CHANGED));
    }
    private Gs1MailClient.Letter validatedLetter(Gs1Repository.Request request,Gs1Repository.PrefixRequestReview review){
        Quantity.fromValue(review.quantity());var letter=letters.letter(request);
        if(!request.inn().equals(review.inn())||!request.id().equals(review.requestId())||!RECIPIENT.equals(letter.recipient())||letter.attachments().size()!=1
                ||!"application/pdf".equals(letter.attachments().getFirst().type())||!digest(letter,request.inn(),review.gcps(),review.quantity()).equals(review.reviewedDigest()))throw new BlockedException(blocked(Block.REVIEW_CHANGED));
        return letter;
    }
    private static void validateDocument(Gs1MailClient.Attachment document)throws IOException{
        if(!"application/pdf".equals(document.type()))throw new IllegalArgumentException("gs1.prefix.document_required");
        var preview=new Gs1PrefixDocumentService().previewTemplate(document.bytes());
        var image=ImageIO.read(new ByteArrayInputStream(preview.png()));int dark=0,min=255,max=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int rgb=image.getRGB(x,y),light=(((rgb>>16)&255)+((rgb>>8)&255)+(rgb&255))/3;min=Math.min(min,light);max=Math.max(max,light);if(light<220)dark++;
        }
        if(dark<150||max-min<30)throw new IllegalArgumentException("gs1.prefix.document_blank");
    }
    private static List<String> activeGcps(Gs1Membership member,Instant now){return member.visiblePrefixes(now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate()).stream().map(Gs1Membership.Prefix::gcp).distinct().toList();}
    private static JsonObject applicant(JsonObject form){return form.has("applicant")&&form.get("applicant").isJsonObject()?form.getAsJsonObject("applicant"):new JsonObject();}
    private static String text(JsonObject object,String key){var value=object.get(key);return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?value.getAsString():"";}
    private static Preflight blocked(Block block){return new Preflight(block,OptionalLong.empty());}
    private static void require(Preflight check){if(!check.canSend())throw new BlockedException(check);}
    private static String digest(Gs1MailClient.Letter letter,String inn,List<String> gcps,int quantity){
        var data=new JsonObject();data.addProperty("inn",inn);data.addProperty("id",letter.id());data.addProperty("parent",letter.threadId());data.addProperty("recipient",letter.recipient());data.addProperty("subject",letter.subject());data.addProperty("body",letter.body());data.add("gcps",new Gson().toJsonTree(gcps));data.addProperty("quantity",quantity);
        var documents=new JsonArray();for(var attachment:letter.attachments()){var doc=new JsonObject();doc.addProperty("name",attachment.name());doc.addProperty("type",attachment.type());doc.addProperty("digest",sha256(attachment.bytes()));documents.add(doc);}data.add("documents",documents);
        return sha256(data.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}}
}
