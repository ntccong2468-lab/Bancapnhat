package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.features.gs1.*;
import com.vncode.app.integration.gs1.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** GS1 uses the VN code shop context; construction and shop selection never contact an upstream. */
public final class Gs1Pane extends BorderPane {
    private final Gs1Repository repository=new Gs1Repository();
    private final Gs1LettersService letters=new Gs1LettersService(repository);
    private final I18nService i18n=I18nService.getInstance();
    private final Consumer<AppLanguage> languageListener=language->Platform.runLater(()->{if(!this.disposed)render();});
    private final AtomicInteger tasks=new AtomicInteger();
    private final ListView<Gs1Repository.Request> requests=new ListView<>();
    private final VBox conversation=new VBox(12);
    private final ScrollPane conversationScroll=new ScrollPane(conversation);
    private final Label summary=new Label();
    private final Label detail=new Label();
    private final Label status=new Label();
    private Shop shop;
    private String inn="";
    private Gs1Membership membership;
    private long generation;
    private boolean busy,disposed;
    private final javafx.beans.property.BooleanProperty busyState=new javafx.beans.property.SimpleBooleanProperty(false);
    javafx.beans.property.ReadOnlyBooleanProperty busyProperty(){return busyState;}
    private IntConsumer onUnread=count->{};
    private record Context(Shop shop,ZnackModels.Settings settings,String inn,String fingerprint){}
    private record Application(NationalCatalogGs1Client.Session session,JsonObject form,Map<String,List<NationalCatalogGs1Client.Choice>> dictionaries){}
    public Gs1Pane(){
        setPadding(new Insets(20));getStyleClass().add("gs1-workspace");
        requests.setCellFactory(list->new ListCell<>(){@Override protected void updateItem(Gs1Repository.Request item,boolean empty){super.updateItem(item,empty);setText(empty||item==null?null:item.subject()+"\n"+tr("gs1.state."+item.state().name())+" · "+displayTime(item.createdAt()));}});
        requests.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showThread(b));
        i18n.addListener(languageListener);render();
    }
    public void setOnUnreadChanged(IntConsumer callback){onUnread=Objects.requireNonNull(callback);updateUnread();}
    public int activeTaskCount(){return tasks.get();}
    public void setShop(Shop selected){
        generation++;shop=selected;busy=false;busyState.set(false);inn="";membership=null;
        if(shop!=null)try{inn=context().inn();membership=repository.membership(inn).orElse(null);}catch(RuntimeException unconfigured){/* The user can configure the signer in Znack settings. */}
        status.setText("");render();reloadRequests();
    }
    private Context context(){
        if(shop==null)throw new IllegalStateException("gs1.no_shop");
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        if(settings.signerTestedAt()==null||settings.signerCertificate()==null||settings.signerCertificate().isBlank())throw new IllegalStateException("gs1.no_signer");
        try{
            JsonObject certificate=JsonParser.parseString(settings.certificateMetadataJson()).getAsJsonObject();
            String owner=NationalCatalogGs1Client.text(certificate,"inn"),fingerprint=NationalCatalogGs1Client.text(certificate,"thumbprint").replaceAll("\\s","").toUpperCase(Locale.ROOT);
            if(!fingerprint.matches("[A-F0-9]{40}")||!certificate.has("hasPrivateKey")||!certificate.get("hasPrivateKey").getAsBoolean())throw new IllegalArgumentException();
            String selector=settings.signerCertificate().replaceAll("\\s","").toUpperCase(Locale.ROOT);
            if(!selector.equals(fingerprint)&&!settings.signerCertificate().equals(NationalCatalogGs1Client.text(certificate,"selector")))throw new IllegalArgumentException();
            Instant now=Instant.now();
            if(!certificate.has("validFrom")||!certificate.has("validTo")||Instant.parse(certificate.get("validFrom").getAsString()).isAfter(now)||!Instant.parse(certificate.get("validTo").getAsString()).isAfter(now))throw new IllegalArgumentException();
            Gs1Membership.requireInn(owner);
            if(settings.participantInn()!=null&&!settings.participantInn().isBlank()&&!owner.equals(settings.participantInn()))throw new IllegalArgumentException();
            return new Context(shop,settings,owner,fingerprint);
        }catch(RuntimeException invalid){throw new IllegalStateException("gs1.no_signer");}
    }
    private NationalCatalogGs1Client.Session login(Context context)throws IOException{
        ZnackSigningSession.authorizeShop(context.shop().getId());
        var provider=new CryptoProSignatureProvider(context.settings().cryptcpPath(),context.fingerprint(),Duration.ofSeconds(context.settings().resolvedCryptoProTimeoutSeconds()));
        return new NationalCatalogGs1Client().login(context.inn(),context.fingerprint(),ZnackSigningSession.guard(context.shop().getId(),context.fingerprint(),provider));
    }
    private void render(){
        Label title=new Label(tr("gs1.title"));title.getStyleClass().add("h1");
        Label guide=new Label(tr("gs1.guide"));guide.setWrapText(true);guide.getStyleClass().add("text-muted");
        FlowPane actions=new FlowPane(8,8);
        actions.getChildren().addAll(button("gs1.refresh",this::refreshMembership),button("gs1.join",this::join),button("gs1.renew",()->draft(Gs1LettersService.Kind.RENEWAL)),button("gs1.more_codes",()->draft(Gs1LettersService.Kind.MORE_CODES)),button("gs1.catalog_issue",()->draft(Gs1LettersService.Kind.CATALOG_ISSUE)),button("gs1.mail_settings",this::mailSettings),button("gs1.inbox_refresh",this::refreshInbox));
        actions.setDisable(busy||inn.isBlank());
        summary.setWrapText(true);summary.getStyleClass().setAll("label","h2");detail.setWrapText(true);detail.getStyleClass().setAll("label","text-muted");status.setWrapText(true);
        String assertion=membership==null?tr("gs1.status.UNKNOWN"):tr("gs1.status."+membership.status(LocalDate.now(ZoneId.of("Europe/Moscow"))).name());
        summary.setText(inn.isBlank()?tr(shop==null?"gs1.no_shop":"gs1.no_signer"):(membership==null?"INN: "+inn:membership.legalName()+" · INN: "+inn)+"\n"+assertion);
        String facts=membership==null?tr("gs1.not_checked"):tr("gs1.expiry")+": "+(membership.expiry()==null?"—":membership.expiry())+"\n"+tr("gs1.checked_at")+": "+displayTime(membership.checkedAt())+" · "+(membership.stale(Instant.now())?tr("gs1.stale"):tr("gs1.cached"))+"\n"+tr("gs1.source_catalog");
        if(membership!=null)for(var prefix:membership.visiblePrefixes(LocalDate.now(ZoneId.of("Europe/Moscow"))))facts+="\nGCP: "+prefix.gcp()+" · "+tr("gs1.allowance")+": "+Objects.toString(prefix.gtinsLeft(),"—")+" · GLN: "+String.join(", ",prefix.glns());
        detail.setText(facts);
        Hyperlink portal=new Hyperlink(tr("gs1.official_portal"));portal.setOnAction(e->openPortal());
        VBox header=new VBox(12,title,guide,actions,summary,detail,portal,status);header.setPadding(new Insets(0,0,16,0));setTop(header);
        VBox left=new VBox(8,new Label(tr("gs1.requests")),requests);VBox.setVgrow(requests,Priority.ALWAYS);left.setMinWidth(220);left.setPrefWidth(285);
        conversationScroll.setFitToWidth(true);conversationScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        SplitPane split=new SplitPane(left,conversationScroll);split.setDividerPositions(.3);setCenter(split);
        requests.refresh();if(requests.getSelectionModel().getSelectedItem()!=null)showThread(requests.getSelectionModel().getSelectedItem());
    }
    private void refreshMembership(){
        Context owner;try{owner=context();}catch(RuntimeException error){showError(error);return;}
        run(()->{var snapshot=login(owner).membership();repository.saveMembership(snapshot);return snapshot;},snapshot->{membership=snapshot;status.setText(tr("gs1.refreshed"));render();});
    }
    private void join(){
        Context owner=context();
        run(()->{
            var session=login(owner);var form=session.form();Map<String,List<NationalCatalogGs1Client.Choice>> dictionaries=new LinkedHashMap<>();
            for(String name:List.of("opf","positions","profiles-organization","enterprise-sector","main-products","documents","provider","participation","selling-ways","distribution-channels"))dictionaries.put(name,session.dictionary(name));
            return new Application(session,form,Map.copyOf(dictionaries));
        },application->new Gs1JoinDialog(this,owner.inn(),application.form(),application.dictionaries(),
            query->application.session().addresses(query),
            (form,submit)->{
                if(submit){
                    if(!confirm("gs1.confirm_save",tr("gs1.prepare_document_notice")))return;
                    run(()->new Gs1ApplicationService(repository).prepareSigning(application.session(),form),prepared->{reloadRequests();reviewSigningDocument(owner,application.session(),prepared);});
                }else{
                    if(!confirm("gs1.confirm_save",tr("gs1.save_notice")))return;
                    run(()->{application.session().saveForm(form);return application.session().form();},saved->{status.setText(tr("gs1.saved"));reloadRequests();});
                }
            }).show());
    }
    private void reviewSigningDocument(Context owner,NationalCatalogGs1Client.Session session,Gs1ApplicationService.PreparedApplication prepared){
        Dialog<Void> dialog=dialog(tr("gs1.review_document"));dialog.getDialogPane().setPrefSize(850,650);
        TextArea content=new TextArea(prepared.description());content.setEditable(false);content.setWrapText(true);
        TextArea xml=new TextArea(prepared.xml());xml.setEditable(false);
        Tab summaryTab=new Tab(tr("gs1.review"),content),xmlTab=new Tab("XML",xml);summaryTab.setClosable(false);xmlTab.setClosable(false);
        TabPane pages=new TabPane(summaryTab,xmlTab);
        Label notice=new Label(tr("gs1.exact_document_notice")+"\nINN: "+prepared.inn()+"\n"+tr("gs1.certificate")+": "+owner.fingerprint());notice.setWrapText(true);
        Label feedback=new Label();feedback.textProperty().bind(status.textProperty());feedback.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(12,notice,pages,feedback));
        ButtonType sign=new ButtonType(tr("gs1.sign_submit"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(sign,ButtonType.CANCEL);
        Button send=(Button)dialog.getDialogPane().lookupButton(sign);send.disableProperty().bind(busyState);
        send.addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            event.consume();if(!inn.equals(owner.inn())||disposed)return;
            var signer=ZnackSigningSession.guardXml(owner.shop().getId(),owner.fingerprint(),XmlSignatureProvider.forCertificate(owner.fingerprint(),Duration.ofSeconds(Math.max(300,owner.settings().resolvedCryptoProTimeoutSeconds()))));
            run(()->new Gs1ApplicationService(repository).submit(session,prepared,signer::signXml),state->{dialog.close();status.setText(tr("gs1.state."+state.name()));reloadRequests();});
        });
        dialog.showAndWait();feedback.textProperty().unbind();
    }
    private void draft(Gs1LettersService.Kind kind){
        Context owner=context();
        Dialog<Gs1Repository.Request> dialog=dialog(tr("gs1.request."+kind.name()));
        TextField name=new TextField(membership==null?"":membership.legalName());TextField amount=new TextField("1000");TextArea note=new TextArea();note.setWrapText(true);note.setPrefRowCount(4);
        GridPane grid=grid();row(grid,0,tr("gs1.legal_name"),name);if(kind==Gs1LettersService.Kind.MORE_CODES)row(grid,1,tr("gs1.quantity"),amount);row(grid,2,tr("gs1.comment"),note);
        ButtonType next=new ButtonType(tr("gs1.preview"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(next,ButtonType.CANCEL);dialog.getDialogPane().setContent(grid);
        var prepared=new java.util.concurrent.atomic.AtomicReference<Gs1Repository.Request>();
        dialog.setResultConverter(type->type==next?prepared.get():null);
        ((Button)dialog.getDialogPane().lookupButton(next)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            try{Integer count=kind==Gs1LettersService.Kind.MORE_CODES?Integer.valueOf(amount.getText().strip()):null;
                var request=letters.draft(owner.inn(),name.getText(),kind,count,note.getText());prepared.set(request);
            }catch(RuntimeException invalid){event.consume();showError(new IllegalStateException("gs1.invalid_fields"));}
        });
        dialog.showAndWait().ifPresent(request->{reloadRequests();previewLetter(request);});
    }
    private void previewLetter(Gs1Repository.Request request){
        Dialog<Void> preview=dialog(tr("gs1.preview"));TextArea content=new TextArea(request.body());content.setEditable(false);content.setWrapText(true);content.setPrefRowCount(15);
        var account=repository.mailAccount(request.inn());Label header=new Label(tr("gs1.from")+": "+account.map(Gs1MailAccount::from).orElse(tr("gs1.mail_not_configured"))+"\n"+tr("gs1.to")+": "+request.recipient()+"\n"+request.subject());header.setWrapText(true);
        VBox box=new VBox(10,header,content,new Label(tr("gs1.sent_not_accepted")));
        for(var attachment:letters.letter(request).attachments())box.getChildren().add(new Label(attachment.name()+" · "+attachment.bytes().length+" bytes"));
        ButtonType send=new ButtonType(tr("gs1.send"),ButtonBar.ButtonData.OK_DONE),export=new ButtonType(tr("gs1.export"),ButtonBar.ButtonData.OTHER);
        preview.getDialogPane().getButtonTypes().addAll(send,export,ButtonType.CANCEL);preview.getDialogPane().setContent(box);
        preview.getDialogPane().lookupButton(send).setDisable(account.isEmpty()||request.state()!=Gs1Repository.State.DRAFT);
        ((Button)preview.getDialogPane().lookupButton(send)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            if(!confirm("gs1.confirm_send",tr("gs1.to")+": "+request.recipient())){event.consume();return;}
            run(()->{letters.send(account.orElseThrow(),request.inn(),request.id());return true;},done->{status.setText(tr("gs1.sent_not_accepted"));reloadRequests();});
        });
        ((Button)preview.getDialogPane().lookupButton(export)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{event.consume();exportLetter(request,account.map(Gs1MailAccount::from).orElse("owner@vncode.local"));});
        preview.showAndWait();
    }
    private void exportLetter(Gs1Repository.Request request,String from){
        FileChooser chooser=new FileChooser();chooser.setInitialFileName("GS1-"+request.id()+".eml");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Email","*.eml"));File file=chooser.showSaveDialog(getScene()==null?null:getScene().getWindow());
        if(file!=null)run(()->{Files.write(file.toPath(),new Gs1MailClient().export(from,letters.letter(request)));return true;},done->status.setText(tr("gs1.exported")));
    }
    private void mailSettings(){
        Context owner=context();var existing=repository.mailAccount(owner.inn());Dialog<Void> dialog=dialog(tr("gs1.mail_settings"));GridPane grid=grid();
        TextField smtp=new TextField(existing.map(Gs1MailAccount::smtpHost).orElse("")),smtpPort=new TextField(existing.map(a->Integer.toString(a.smtpPort())).orElse("465")),imap=new TextField(existing.map(Gs1MailAccount::imapHost).orElse("")),imapPort=new TextField(existing.map(a->Integer.toString(a.imapPort())).orElse("993")),username=new TextField(existing.map(Gs1MailAccount::username).orElse("")),from=new TextField(existing.map(Gs1MailAccount::from).orElse(""));PasswordField password=new PasswordField();
        row(grid,0,"SMTP TLS",smtp);row(grid,1,tr("gs1.port"),smtpPort);row(grid,2,"IMAP TLS",imap);row(grid,3,tr("gs1.port"),imapPort);row(grid,4,tr("gs1.username"),username);row(grid,5,tr("gs1.from"),from);row(grid,6,tr("gs1.password"),password);
        Label hint=new Label(tr("gs1.mail_security"));hint.setWrapText(true);Label feedback=new Label();feedback.setWrapText(true);feedback.textProperty().bind(status.textProperty());dialog.getDialogPane().setContent(new VBox(12,hint,grid,feedback));ButtonType save=new ButtonType(tr("gs1.save"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);if(!WindowsSecretProtector.supported())dialog.getDialogPane().lookupButton(save).setDisable(true);else dialog.getDialogPane().lookupButton(save).disableProperty().bind(busyState);
        ((Button)dialog.getDialogPane().lookupButton(save)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            event.consume();
            try{
                String smtpHost=smtp.getText().strip(),imapHost=imap.getText().strip(),user=username.getText().strip(),sender=from.getText().strip(),secret=password.getText();int sp=Integer.parseInt(smtpPort.getText()),ip=Integer.parseInt(imapPort.getText());
                new Gs1MailAccount(smtpHost,sp,imapHost,ip,user,sender,"dpapi:validation");
                if(secret.isEmpty()&&existing.isEmpty())throw new IllegalArgumentException();
                run(()->{String protectedValue=secret.isEmpty()?existing.orElseThrow().protectedPassword():new WindowsSecretProtector().protect(secret);repository.saveMailAccount(owner.inn(),new Gs1MailAccount(smtpHost,sp,imapHost,ip,user,sender,protectedValue));return true;},done->{password.clear();dialog.close();status.setText(tr("gs1.saved"));});
            }catch(RuntimeException invalid){event.consume();showError(new IllegalStateException("gs1.invalid_fields"));}
        });dialog.showAndWait();password.clear();feedback.textProperty().unbind();
    }
    private void refreshInbox(){
        Context owner=context();var account=repository.mailAccount(owner.inn());if(account.isEmpty()){showError(new IllegalStateException("gs1.mail_not_configured"));return;}
        run(()->letters.refresh(account.get(),owner.inn()),count->{reloadRequests();status.setText(tr("gs1.refreshed"));});
    }
    private void reloadRequests(){
        String selected=requests.getSelectionModel().getSelectedItem()==null?null:requests.getSelectionModel().getSelectedItem().id();
        requests.getItems().setAll(inn.isBlank()?List.of():repository.requests(inn));
        requests.getItems().stream().filter(r->r.id().equals(selected)).findFirst().ifPresent(r->requests.getSelectionModel().select(r));updateUnread();
        if(requests.getItems().isEmpty()){conversation.getChildren().setAll(new Label(tr("gs1.empty_letters")));}
    }
    private void showThread(Gs1Repository.Request request){
        conversation.getChildren().clear();if(request==null||!request.inn().equals(inn))return;
        Label heading=new Label(request.subject());heading.getStyleClass().add("h2");conversation.getChildren().add(heading);
        Label state=new Label(tr("gs1.state."+request.state().name()));state.setWrapText(true);conversation.getChildren().add(state);
        if(request.kind().equals("JOIN")){
            conversation.getChildren().add(button("gs1.reconcile",()->{Context owner=context();run(()->new Gs1ApplicationService(repository).reconcile(login(owner),request.id()),result->reloadRequests());}));
        }else{
            conversation.getChildren().add(button("gs1.preview",()->previewLetter(request)));
            Button proof=button("gs1.payment_proof",()->paymentProof(request));proof.setDisable(!letters.canPreparePaymentProof(inn,request.id()));conversation.getChildren().add(proof);
            if(request.state()==Gs1Repository.State.SENDING||request.state()==Gs1Repository.State.RECONCILE_REQUIRED)conversation.getChildren().add(button("gs1.reconcile_mail",()->reconcileMail(request)));
        }
        List<Gs1Repository.Message> messages=repository.messages(inn,request.id());
        if(messages.isEmpty()){Label draft=new Label(request.kind().equals("JOIN")?tr("gs1.submit_notice"):request.body());draft.setWrapText(true);conversation.getChildren().add(draft);}
        for(var message:messages){
            Label text=new Label();text.setWrapText(true);String[] lines=message.body().split("\n",-1);boolean collapsed=lines.length>10||message.body().length()>1600;
            String compact=String.join("\n",Arrays.copyOf(lines,Math.min(10,lines.length)));if(compact.length()>1600)compact=compact.substring(0,1600)+"…";text.setText(collapsed?compact:message.body());
            VBox bubble=new VBox(7,new Label((message.outgoing()?tr("gs1.you"):message.sender())+" · "+displayTime(message.createdAt())),text);bubble.setPadding(new Insets(12));bubble.getStyleClass().add("dashboard-feature-card");bubble.setMaxWidth(650);
            if(!message.outgoing()){Label provenance=new Label(tr("gs1.mail_unverified"));provenance.setWrapText(true);provenance.getStyleClass().add("text-muted");bubble.getChildren().add(1,provenance);}
            if(collapsed){Button expand=new Button(tr("gs1.expand"));expand.setOnAction(e->{boolean full=expand.getText().equals(tr("gs1.expand"));text.setText(full?message.body():String.join("\n",Arrays.copyOf(lines,Math.min(10,lines.length))));expand.setText(tr(full?"gs1.collapse":"gs1.expand"));});bubble.getChildren().add(expand);}
            for(var attachment:repository.attachments(inn,request.id(),message.id())){Button download=new Button(attachment.name());download.setOnAction(e->downloadAttachment(attachment));bubble.getChildren().add(download);}
            if(!message.outgoing()){boolean verified=repository.hasConfirmedInvoice(request.inn(),request.id());Button invoice=button(verified?"gs1.invoice_confirmed":"gs1.confirm_invoice",()->confirmPortalInvoice(request,message));invoice.setDisable(verified||request.kind().startsWith("PAYMENT_PROOF:"));bubble.getChildren().add(invoice);}
            HBox aligned=new HBox(bubble);aligned.setAlignment(message.outgoing()?Pos.TOP_RIGHT:Pos.TOP_LEFT);conversation.getChildren().add(aligned);
        }
        repository.markRead(inn,request.id());updateUnread();
        long expected=generation;String threadId=request.id();
        Platform.runLater(()->{var selected=requests.getSelectionModel().getSelectedItem();if(!disposed&&generation==expected&&selected!=null&&selected.id().equals(threadId)){conversation.applyCss();conversation.layout();conversationScroll.layout();conversationScroll.setVvalue(1);}});
    }
    private void confirmPortalInvoice(Gs1Repository.Request request,Gs1Repository.Message message){
        Dialog<Void> dialog=dialog(tr("gs1.confirm_invoice"));GridPane fields=grid();
        TextField enterprise=new TextField(),number=new TextField(),amount=new TextField();
        row(fields,0,"INN",enterprise);row(fields,1,tr("gs1.invoice_number"),number);row(fields,2,tr("gs1.invoice_amount")+" (RUB)",amount);
        Label notice=new Label(tr("gs1.invoice_notice"));notice.setWrapText(true);
        Hyperlink portal=new Hyperlink(tr("gs1.official_portal"));portal.setOnAction(e->openPortal());
        CheckBox verified=new CheckBox(tr("gs1.invoice_portal_checked"));verified.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(12,notice,portal,fields,verified));
        ButtonType save=new ButtonType(tr("gs1.confirm_invoice"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        ((Button)dialog.getDialogPane().lookupButton(save)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            try{if(!verified.isSelected())throw new IllegalArgumentException();repository.confirmInvoice(request.inn(),request.id(),message.id(),new Gs1Repository.PortalInvoice(enterprise.getText().strip(),number.getText(),amount.getText().strip(),"RUB"));showThread(request);}
            catch(RuntimeException invalid){event.consume();notice.setText(tr("gs1.invalid_fields"));}
        });dialog.showAndWait();
    }
    private void reconcileMail(Gs1Repository.Request request){
        var account=repository.mailAccount(request.inn());if(account.isEmpty()){showError(new IllegalStateException("gs1.mail_not_configured"));return;}
        if(!confirm("gs1.reconcile_mail",tr("gs1.sent_copy_notice")))return;
        FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Email","*.eml"));File file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;
        run(()->{if(Files.size(file.toPath())>15*1024*1024)throw new IOException("Mail evidence too large");return letters.reconcileSent(account.get(),request.inn(),request.id(),Files.readAllBytes(file.toPath()));},state->{status.setText(tr("gs1.sent_copy_confirmed"));reloadRequests();});
    }
    private void paymentProof(Gs1Repository.Request request){
        FileChooser chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("gs1.documents"),"*.pdf","*.png","*.jpg","*.jpeg"));File file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;
        run(()->{if(Files.size(file.toPath())>Gs1MailClient.MAX_ATTACHMENT)throw new IOException("Attachment too large");String extension=file.getName().toLowerCase(Locale.ROOT);String type=extension.endsWith(".pdf")?"application/pdf":extension.endsWith(".png")?"image/png":"image/jpeg";return letters.preparePaymentProof(request.inn(),request.id(),new Gs1MailClient.Attachment(file.getName(),type,Files.readAllBytes(file.toPath())));},proof->{reloadRequests();previewLetter(proof);});
    }
    private void downloadAttachment(Gs1MailClient.Attachment attachment){
        FileChooser chooser=new FileChooser();chooser.setInitialFileName(attachment.name());File file=chooser.showSaveDialog(getScene()==null?null:getScene().getWindow());if(file!=null)run(()->{Files.write(file.toPath(),attachment.bytes());return true;},done->status.setText(tr("gs1.saved")));
    }
    private <T> void run(Callable<T> work,Consumer<T> done){
        if(busy||disposed)return;
        long expected=generation;busy=true;busyState.set(true);status.setText(tr("gs1.loading"));render();tasks.incrementAndGet();
        Task<T> task=new Task<>(){@Override protected T call()throws Exception{try{return work.call();}finally{tasks.decrementAndGet();}}};
        task.setOnSucceeded(e->{if(disposed||generation!=expected)return;busy=false;busyState.set(false);render();done.accept(task.getValue());});
        task.setOnFailed(e->{if(disposed||generation!=expected)return;busy=false;busyState.set(false);render();reloadRequests();showError(task.getException());});AppTaskExecutor.execute(task);
    }
    private Button button(String key,Runnable action){Button button=new Button(tr(key));button.setOnAction(e->{if(busy||disposed)return;try{action.run();}catch(RuntimeException failure){showError(failure);}});return button;}
    private void updateUnread(){onUnread.accept(inn.isBlank()?0:repository.unread(inn));}
    private void showError(Throwable error){String message=error.getMessage();status.setText(tr(message!=null&&message.startsWith("gs1.")?message:"gs1.error"));}
    private boolean confirm(String title,String body){Alert alert=new Alert(Alert.AlertType.CONFIRMATION,body,ButtonType.YES,ButtonType.NO);AlertService.applyTheme(alert);if(getScene()!=null)alert.initOwner(getScene().getWindow());alert.setTitle(tr(title));alert.setHeaderText(tr(title));return alert.showAndWait().orElse(ButtonType.NO)==ButtonType.YES;}
    private <T> Dialog<T> dialog(String title){Dialog<T> dialog=new Dialog<>();AlertService.applyTheme(dialog);if(getScene()!=null)dialog.initOwner(getScene().getWindow());dialog.setTitle(title);dialog.getDialogPane().setPrefWidth(650);return dialog;}
    private static GridPane grid(){GridPane grid=new GridPane();grid.setHgap(14);grid.setVgap(10);return grid;}
    private static void row(GridPane grid,int row,String label,Node input){grid.addRow(row,new Label(label),input);GridPane.setHgrow(input,Priority.ALWAYS);}
    private String tr(String key){return i18n.tr(key);}
    private static String displayTime(Instant value){return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(value);}
    private void openPortal(){try{java.awt.Desktop.getDesktop().browse(java.net.URI.create(NationalCatalogGs1Client.PROFILE_URL));}catch(Exception unavailable){showError(new IllegalStateException("gs1.error"));}}
    public void dispose(){disposed=true;generation++;i18n.removeListener(languageListener);}
}
