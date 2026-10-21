package com.vncode.app.ui.gs1;

import com.vncode.app.features.gs1.*;
import com.vncode.app.features.gs1.Gs1PrefixRequestModels.*;
import com.vncode.app.integration.gs1.*;
import com.vncode.app.shared.*;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Catalog fields come from one authenticated enterprise. Only templates and signed documents are imported.
 * The result is a reviewed draft; the caller performs a separate confirmed, freshly guarded send.
 */
public final class Gs1PrefixRequestDialog {
    private final Dialog<RequestDraft> dialog=new Dialog<>();
    private final Gs1PrefixRequestService service;
    private final Gs1PrefixTemplateStore templates=new Gs1PrefixTemplateStore();
    private final Gs1WordPrefixService words=new Gs1WordPrefixService();
    private final Gs1PrefixDocumentService documents=new Gs1PrefixDocumentService();
    private final Gs1SignatureInk ink=new Gs1SignatureInk();
    private final ProfileDefaults defaults;
    private final Gs1PrefixRequestService.Preflight preflight;
    private final TextField company, email, phone, signer;
    private final DatePicker date=new DatePicker(LocalDate.now(ZoneId.of("Europe/Moscow")));
    private final TextArea comment=new TextArea(), mail=new TextArea();
    private final ToggleGroup quantity=new ToggleGroup();
    private final Canvas signature=new Canvas(440,120);
    private final ImageView preview=new ImageView();
    private final Label status=new Label(), templateStatus=new Label();
    private final CheckBox reviewed=new CheckBox(tr("gs1.prefix.confirm"));
    private final Button importWord=new Button(tr("gs1.prefix.import_word")), downloadWord=new Button(tr("gs1.prefix.download_word")),
            previewWord=new Button(tr("gs1.prefix.preview_word")), sign=new Button(tr("gs1.prefix.sign")),
            attach=new Button(tr("gs1.prefix.attach_signed")), clear=new Button(tr("gs1.document_clear_signature"));
    private final ButtonType next=new ButtonType(tr("gs1.preview"),ButtonBar.ButtonData.OK_DONE);
    private final VBox inputs;
    private byte[] template;
    private Gs1MailClient.Attachment document;
    private boolean wordDocument, processing, disposed, actualPreview;
    private RequestDraft result;

    Gs1PrefixRequestDialog(Window owner, ProfileDefaults defaults, Gs1PrefixRequestService service,
                           Gs1PrefixRequestService.Preflight preflight) {
        this.defaults=Objects.requireNonNull(defaults);this.service=Objects.requireNonNull(service);this.preflight=Objects.requireNonNull(preflight);
        Gs1Membership.requireInn(defaults.inn());
        if(owner!=null)dialog.initOwner(owner);AlertService.applyTheme(dialog);Gs1Dialogs.centerOnOwner(dialog);
        dialog.setTitle(tr("gs1.request.MORE_CODES"));dialog.setResizable(true);
        company=input(defaults.legalName(),"gs1PrefixCompany");email=input(defaults.email(),"gs1PrefixEmail");
        phone=input(defaults.phone(),"gs1PrefixPhone");signer=input(defaults.signerName(),"gs1PrefixSigner");
        var inn=input(defaults.inn(),"gs1PrefixInn");inn.setEditable(false);
        var gcps=input(String.join(", ",defaults.gcps()),"gs1PrefixGcps");gcps.setEditable(false);
        inputs=new VBox(7,labeled("gs1.legal_name",company),labeled("gs1.field.inn",inn),labeled("gs1.prefix.gcp",gcps),
                labeled("gs1.prefix.email",email),labeled("gs1.prefix.phone",phone),labeled("gs1.document_field.signer_name",signer),labeled("gs1.document_field.date",date));
        Label source=new Label(tr("gs1.prefix.profile_source"));source.setWrapText(true);
        Label recipient=new Label(tr("gs1.to")+": "+Gs1PrefixRequestService.RECIPIENT);recipient.setId("gs1PrefixRecipient");
        var radios=new VBox(7);
        for(var choice:Quantity.values()){
            var radio=new RadioButton(tr("gs1.prefix.quantity."+choice.name()));radio.setToggleGroup(quantity);radio.setUserData(choice);
            radio.setId("gs1PrefixQuantity"+choice.name());radio.setWrapText(true);radios.getChildren().add(radio);
            if(choice==Quantity.STANDARD)radio.setSelected(true);
        }
        Label allowance=new Label(tr(preflight.messageKey())+(preflight.remaining().isPresent()?"\n"+tr("gs1.prefix.total_remaining").replace("{0}",Long.toString(preflight.remaining().getAsLong())):""));
        allowance.setId("gs1PrefixAllowance");allowance.setWrapText(true);allowance.getStyleClass().add(preflight.canSend()?"text-muted":"text-warning");
        Label templateNotice=new Label(tr("gs1.prefix.template_notice"));templateNotice.setWrapText(true);templateStatus.setWrapText(true);
        var wordButtons=new FlowPane(8,8,importWord,downloadWord,previewWord);
        signature.setId("gs1PrefixSignature");var signatureBox=new Pane(signature);signatureBox.setStyle("-fx-border-color: #777; -fx-background-color: white;");
        Label signatureNotice=new Label(tr("gs1.document_signature_notice"));signatureNotice.setWrapText(true);
        var signatureSection=new TitledPane(tr("gs1.prefix.sign"),new VBox(8,signatureNotice,signatureBox,new HBox(8,clear,sign)));signatureSection.setExpanded(false);
        comment.setWrapText(true);comment.setPrefRowCount(3);comment.setId("gs1PrefixComment");
        mail.setEditable(false);mail.setWrapText(true);mail.setPrefRowCount(8);mail.setId("gs1PrefixMailPreview");
        var mailSection=new TitledPane(tr("gs1.prefix.mail_preview"),mail);mailSection.setExpanded(false);
        reviewed.setWrapText(true);reviewed.setId("gs1PrefixReviewConfirmation");status.setWrapText(true);
        var editor=new VBox(12,recipient,source,inputs,radios,allowance,templateNotice,wordButtons,templateStatus,signatureSection,attach,
                labeled("gs1.comment",comment),mailSection,reviewed,status);editor.setPadding(new Insets(12));editor.setMinWidth(0);editor.setPrefWidth(470);
        var editorScroll=new ScrollPane(editor);editorScroll.setFitToWidth(true);editorScroll.setPrefViewportHeight(650);
        preview.setId("gs1PrefixActualPdf");preview.setFitWidth(440);preview.setPreserveRatio(true);
        Label previewNotice=new Label(tr("gs1.prefix.preview_notice"));previewNotice.setWrapText(true);
        var previewBox=new VBox(8,previewNotice,preview);previewBox.setPadding(new Insets(10));
        var previewScroll=new ScrollPane(previewBox);previewScroll.setFitToWidth(true);previewScroll.setPrefViewportWidth(465);previewScroll.setPrefViewportHeight(650);
        var body=new SplitPane(editorScroll,previewScroll);body.setDividerPositions(.51);
        dialog.getDialogPane().setContent(body);dialog.getDialogPane().setPrefSize(1000,760);dialog.getDialogPane().getButtonTypes().addAll(next,ButtonType.CANCEL);
        importWord.setId("gs1PrefixImportWord");downloadWord.setId("gs1PrefixDownloadWord");previewWord.setId("gs1PrefixPreviewWord");sign.setId("gs1PrefixCreateSignedPdf");attach.setId("gs1PrefixAttachSigned");
        importWord.setOnAction(e->chooseWord());downloadWord.setOnAction(e->exportWord());previewWord.setOnAction(e->generate(false));
        sign.setOnAction(e->generate(true));attach.setOnAction(e->chooseSigned());clear.setOnAction(e->{ink.clear();invalidate(true);drawInk();});
        for(var field:List.of(company,email,phone,signer))field.textProperty().addListener((o,a,b)->invalidate(true));
        date.valueProperty().addListener((o,a,b)->invalidate(true));comment.textProperty().addListener((o,a,b)->invalidate(false));quantity.selectedToggleProperty().addListener((o,a,b)->invalidate(false));
        reviewed.selectedProperty().addListener((o,a,b)->updateButtons());installInk();drawInk();updateButtons();updateMail();
        ((Button)dialog.getDialogPane().lookupButton(next)).addEventFilter(javafx.event.ActionEvent.ACTION,e->{
            try{result=draft();if(!preflight.canSend()||processing||!actualPreview||!reviewed.isSelected())throw new IllegalArgumentException();}
            catch(RuntimeException invalid){e.consume();status.setText(tr("gs1.prefix.confirm_required"));}
        });
        dialog.setResultConverter(button->button==next?result:null);
        dialog.setOnShown(e->loadSavedTemplate());
        dialog.setOnHidden(e->{
            disposed=true;ink.close();signature.getGraphicsContext2D().clearRect(0,0,signature.getWidth(),signature.getHeight());
            if(template!=null)Arrays.fill(template,(byte)0);template=null;document=null;preview.setImage(null);
            company.clear();email.clear();phone.clear();signer.clear();comment.clear();mail.clear();
        });
    }

    public static Optional<RequestDraft> show(Window owner, ProfileDefaults defaults, Gs1PrefixRequestService service,
                                              Gs1PrefixRequestService.Preflight preflight) {
        return new Gs1PrefixRequestDialog(owner,defaults,service,preflight).dialog.showAndWait();
    }
    private Profile profile(){return new Profile(company.getText(),defaults.inn(),defaults.gcps(),email.getText(),phone.getText(),signer.getText());}
    private Gs1WordPrefixService.Fields fields(){return new Gs1WordPrefixService.Fields(company.getText(),defaults.inn(),defaults.gcps(),date.getValue(),signer.getText());}
    private RequestDraft draft(){return new RequestDraft(profile(),(Quantity)quantity.getSelectedToggle().getUserData(),comment.getText(),Objects.requireNonNull(document));}
    private void updateMail(){
        if(disposed)return;
        try{mail.setText(Gs1PrefixRequestService.body(profile(),(Quantity)quantity.getSelectedToggle().getUserData(),comment.getText()));}
        catch(RuntimeException incomplete){mail.setText(tr("gs1.prefix.complete_fields"));}
    }
    private record Loaded(byte[] bytes, Gs1WordPrefixService.TemplateDescription description){}
    private void loadSavedTemplate(){
        run(new Task<Optional<Loaded>>(){@Override protected Optional<Loaded> call()throws Exception{
            var saved=templates.load(defaults.inn(),"production");if(saved.isEmpty())return Optional.empty();
            var bytes=saved.get();try{return Optional.of(new Loaded(bytes,words.inspect(bytes)));}catch(Exception failure){Arrays.fill(bytes,(byte)0);throw failure;}
        }},value->value.ifPresent(this::acceptTemplate),()->{});
    }
    private void chooseWord(){
        var chooser=new FileChooser();chooser.setTitle(tr("gs1.prefix.import_word"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Word DOCX","*.docx","*.DOCX"));
        var file=chooser.showOpenDialog(window());if(file==null)return;
        run(new Task<Loaded>(){@Override protected Loaded call()throws Exception{
            byte[] bytes=read(file.toPath());try{var description=words.inspect(bytes);templates.save(defaults.inn(),"production",bytes);return new Loaded(bytes,description);}
            catch(Exception failure){Arrays.fill(bytes,(byte)0);throw failure;}
        }},this::acceptTemplate,()->{});
    }
    private void acceptTemplate(Loaded loaded){
        if(template!=null)Arrays.fill(template,(byte)0);template=loaded.bytes();invalidate(true);
        templateStatus.setText(tr("gs1.prefix.template_loaded").replace("{0}",loaded.description().sha256().substring(0,12)));
    }
    private void exportWord(){
        if(template==null)return;Gs1WordPrefixService.Fields fields;
        try{fields=fields();}catch(RuntimeException invalid){status.setText(tr("gs1.prefix.complete_fields"));return;}
        var chooser=new FileChooser();chooser.setTitle(tr("gs1.prefix.download_word"));chooser.setInitialFileName("GS1-01.04.01.docx");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Word DOCX","*.docx"));
        var file=chooser.showSaveDialog(window());if(file==null)return;byte[] source=template.clone();
        run(new Task<Boolean>(){@Override protected Boolean call()throws Exception{
            try{byte[] filled=words.fillWord(source,fields);try{Files.write(file.toPath(),filled);return true;}finally{Arrays.fill(filled,(byte)0);}}
            finally{Arrays.fill(source,(byte)0);}
        }},saved->status.setText(tr("gs1.saved")),()->Arrays.fill(source,(byte)0));
    }
    private record Generated(Gs1MailClient.Attachment document,Gs1PrefixDocumentService.Preview preview){}
    private void generate(boolean signed){
        if(template==null)return;Gs1WordPrefixService.Fields fields;
        try{fields=fields();if(signed&&ink.isEmpty())throw new IllegalArgumentException();}catch(RuntimeException invalid){status.setText(tr(signed?"gs1.prefix.signature_required":"gs1.prefix.complete_fields"));return;}
        byte[] source=template.clone();Gs1SignatureInk workerInk=copyInk();
        run(new Task<Generated>(){@Override protected Generated call()throws Exception{
            try(workerInk){var attachment=signed?words.signedPdf(source,fields,workerInk):new Gs1MailClient.Attachment("GS1-01.04.01-preview.pdf","application/pdf",words.previewPdf(source,fields).pdf());
                return new Generated(attachment,documents.previewTemplate(attachment.bytes()));}
            finally{Arrays.fill(source,(byte)0);}
        }},generated->{
            preview.setImage(new Image(new ByteArrayInputStream(generated.preview().png())));reviewed.setSelected(false);
            if(signed){document=generated.document();wordDocument=true;actualPreview=true;status.setText(tr("gs1.document_ready"));}
            else{document=null;actualPreview=false;status.setText(tr("gs1.prefix.unsigned_preview"));}updateMail();
        },()->{Arrays.fill(source,(byte)0);workerInk.close();});
    }
    private void chooseSigned(){
        var chooser=new FileChooser();chooser.setTitle(tr("gs1.prefix.attach_signed"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF / JPG / PNG","*.pdf","*.jpg","*.jpeg","*.png","*.PDF","*.JPG","*.JPEG","*.PNG"));
        var file=chooser.showOpenDialog(window());if(file==null)return;
        run(new Task<Generated>(){@Override protected Generated call()throws Exception{
            byte[] bytes=read(file.toPath());try{var name=file.getName().toLowerCase(Locale.ROOT);String type=name.endsWith(".pdf")?"application/pdf":name.endsWith(".png")?"image/png":"image/jpeg";
                var attachment=service.importDocument(bytes,type);return new Generated(attachment,documents.previewTemplate(attachment.bytes()));}
            finally{Arrays.fill(bytes,(byte)0);}
        }},generated->{document=generated.document();wordDocument=false;actualPreview=true;reviewed.setSelected(false);ink.clear();drawInk();
            preview.setImage(new Image(new ByteArrayInputStream(generated.preview().png())));status.setText(tr("gs1.document_ready"));updateMail();},()->{});
    }
    private void invalidate(boolean fieldsChanged){
        if(disposed)return;reviewed.setSelected(false);result=null;
        if(fieldsChanged&&wordDocument){document=null;actualPreview=false;preview.setImage(null);}
        updateMail();updateButtons();
    }
    private void updateButtons(){
        importWord.setDisable(processing);attach.setDisable(processing);downloadWord.setDisable(processing||template==null);
        previewWord.setDisable(processing||template==null);sign.setDisable(processing||template==null||ink.isEmpty());clear.setDisable(processing);
        signature.setDisable(processing);inputs.setDisable(processing);comment.setDisable(processing);reviewed.setDisable(processing||document==null||!actualPreview);
        dialog.getDialogPane().lookupButton(next).setDisable(processing||!preflight.canSend()||document==null||!actualPreview||!reviewed.isSelected());
    }
    private <T> void run(Task<T> task,Consumer<T> success,Runnable rejected){
        if(processing||disposed){rejected.run();return;}processing=true;status.setText(tr("gs1.document_working"));updateButtons();
        task.setOnSucceeded(e->{processing=false;if(disposed){
            Object value=task.getValue();if(value instanceof Loaded loaded)Arrays.fill(loaded.bytes(),(byte)0);
            if(value instanceof Optional<?> optional&&optional.orElse(null) instanceof Loaded loaded)Arrays.fill(loaded.bytes(),(byte)0);
            return;
        }success.accept(task.getValue());updateButtons();});
        task.setOnFailed(e->{processing=false;if(disposed)return;String key=task.getException().getMessage();status.setText(tr(key!=null&&key.startsWith("gs1.")?key:"gs1.prefix.document_error"));updateButtons();});
        AppTaskExecutor.execute(task,unavailable->{processing=false;rejected.run();if(!disposed){status.setText(tr("gs1.prefix.document_error"));updateButtons();}});
    }
    private Gs1SignatureInk copyInk(){
        var result=new Gs1SignatureInk();var snapshot=ink.snapshot();try{for(var stroke:snapshot){if(stroke.length==0)continue;result.start(stroke[0][0],stroke[0][1]);for(int i=1;i<stroke.length;i++)result.append(stroke[i][0],stroke[i][1]);result.end();}return result;}
        finally{for(var stroke:snapshot)for(var point:stroke)Arrays.fill(point,0);}
    }
    private void installInk(){
        signature.setOnMousePressed(e->{if(!e.isSynthesized()&&e.getButton()==MouseButton.PRIMARY)drawPoint(e.getX(),e.getY(),true);});
        signature.setOnMouseDragged(e->{if(!e.isSynthesized()&&e.isPrimaryButtonDown())drawPoint(e.getX(),e.getY(),false);});signature.setOnMouseReleased(e->{if(!e.isSynthesized())ink.end();});
        signature.setOnTouchPressed(e->{if(e.getTouchPoints().size()==1)drawPoint(e.getTouchPoint().getX(),e.getTouchPoint().getY(),true);e.consume();});
        signature.setOnTouchMoved(e->{if(e.getTouchPoints().size()==1)drawPoint(e.getTouchPoint().getX(),e.getTouchPoint().getY(),false);e.consume();});signature.setOnTouchReleased(e->{ink.end();e.consume();});
    }
    private void drawPoint(double x,double y,boolean start){
        if(processing||disposed)return;try{if(start)ink.start(clamp(x/signature.getWidth()),clamp(y/signature.getHeight()));else ink.append(clamp(x/signature.getWidth()),clamp(y/signature.getHeight()));invalidate(true);drawInk();}
        catch(IllegalStateException excessive){status.setText(tr("gs1.document_ink_limit"));}
    }
    private void drawInk(){
        var graphics=signature.getGraphicsContext2D();graphics.setFill(Color.WHITE);graphics.fillRect(0,0,signature.getWidth(),signature.getHeight());graphics.setStroke(Color.BLACK);graphics.setLineWidth(2);
        var snapshot=ink.snapshot();try{for(var stroke:snapshot){if(stroke.length<2)continue;graphics.beginPath();graphics.moveTo(stroke[0][0]*signature.getWidth(),stroke[0][1]*signature.getHeight());for(int i=1;i<stroke.length;i++)graphics.lineTo(stroke[i][0]*signature.getWidth(),stroke[i][1]*signature.getHeight());graphics.stroke();}}
        finally{for(var stroke:snapshot)for(var point:stroke)Arrays.fill(point,0);}
    }
    private Window window(){return dialog.getDialogPane().getScene().getWindow();}
    private static byte[] read(Path path)throws IOException{try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(Gs1MailClient.MAX_ATTACHMENT+1);if(bytes.length>Gs1MailClient.MAX_ATTACHMENT){Arrays.fill(bytes,(byte)0);throw new IOException("gs1.word_size");}return bytes;}}
    private static TextField input(String value,String id){var field=new TextField(Objects.requireNonNullElse(value,""));field.setId(id);return field;}
    private static VBox labeled(String key,Node value){return new VBox(3,new Label(tr(key)),value);}
    private static double clamp(double value){return Math.max(0,Math.min(1,value));}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
