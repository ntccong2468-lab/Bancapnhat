package com.vncode.app.ui.gs1;

import com.vncode.app.features.gs1.Gs1AgreementService;
import com.vncode.app.features.gs1.Gs1PdfResourceGuard;
import com.vncode.app.integration.znack.OperatorAgreement;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.stage.Screen;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** The visible PDF, explicit consent and signature all belong to one prepared document.
 * Signing is never retried here. An uncertain result exposes a read-only reconciliation action. */
final class Gs1AgreementDialog {
    interface Operations {
        Gs1AgreementService.Prepared prepare(OperatorAgreement document)throws Exception;
        byte[] pdf(OperatorAgreement document)throws Exception;
        Gs1AgreementService.Result sign(Gs1AgreementService.Prepared document,boolean consent)throws Exception;
        Gs1AgreementService.Result reconcile(String requestId)throws Exception;
        void requireCurrent()throws Exception;
    }
    private final Dialog<Void> dialog=new Dialog<>();
    private final ButtonType closeType=new ButtonType(tr("common.close"),ButtonBar.ButtonData.CANCEL_CLOSE);
    private final Operations operations;
    private final Consumer<Gs1AgreementService.Result> changed;
    private final ListView<OperatorAgreement> documents=new ListView<>();
    private final ImageView image=new ImageView();
    private final Label title=new Label(),status=new Label(),pageLabel=new Label();
    private final CheckBox consent=new CheckBox(tr("gs1.agreements.consent"));
    private final Button open=new Button(tr("gs1.agreements.open_pdf")),save=new Button(tr("gs1.agreements.save_pdf")),
            sign=new Button(tr("gs1.agreements.sign")),check=new Button(tr("gs1.agreements.check_again")),
            previous=new Button(tr("gs1.agreements.previous")),next=new Button(tr("gs1.agreements.next"));
    private final Map<String,String> submittedRequests=new HashMap<>();
    private Gs1AgreementService.Prepared prepared;
    private byte[] viewedPdf;
    private String viewedDigest="";
    private boolean working,mutation,disposed,pdfVisible;
    private int page,pages;
    private long selection;

    Gs1AgreementDialog(Window owner,List<OperatorAgreement> entries,Operations operations,Consumer<Gs1AgreementService.Result> changed){
        this.operations=Objects.requireNonNull(operations);this.changed=Objects.requireNonNull(changed);
        if(owner!=null)dialog.initOwner(owner);AlertService.applyTheme(dialog);Gs1Dialogs.centerOnOwner(dialog);
        dialog.setTitle(tr("gs1.agreements.title"));dialog.setResizable(true);dialog.getDialogPane().setId("gs1AgreementDialog");
        dialog.getDialogPane().getButtonTypes().add(closeType);
        documents.setId("gs1AgreementList");documents.getItems().setAll(entries);documents.setMinWidth(210);documents.setPrefWidth(260);
        documents.setPlaceholder(new Label(tr("gs1.agreements.none")));
        documents.setCellFactory(list->new ListCell<>(){
            {prefWidthProperty().bind(documents.widthProperty().subtract(22));setMinWidth(0);setMaxWidth(USE_PREF_SIZE);}
            @Override protected void updateItem(OperatorAgreement item,boolean empty){
            super.updateItem(item,empty);setText(empty||item==null?null:item.title()+"\n"+item.createdAt()+" · "+state(item));setWrapText(true);
        }});
        title.setWrapText(true);title.getStyleClass().add("h3");status.setWrapText(true);consent.setWrapText(true);consent.setId("gs1AgreementConsent");
        var notice=new Label(tr("gs1.agreements.notice"));notice.setWrapText(true);
        for(var label:List.of(title,status,notice)){label.setMinWidth(0);label.setMaxWidth(Double.MAX_VALUE);label.setMaxHeight(Double.MAX_VALUE);}
        consent.setMinWidth(0);consent.setMaxWidth(Double.MAX_VALUE);
        image.setId("gs1AgreementPdf");image.setPreserveRatio(true);image.setFitWidth(600);
        var pdf=new ScrollPane(image);pdf.setFitToWidth(true);pdf.setPannable(true);pdf.setMinHeight(180);pdf.setPrefViewportHeight(420);
        var pager=new HBox(8,previous,pageLabel,next);var actions=new FlowPane(8,8,open,save,sign,check);
        var body=new VBox(12,title,notice,actions,pager,pdf,consent,status);body.setPadding(new Insets(12));body.setMinWidth(0);VBox.setVgrow(pdf,Priority.ALWAYS);
        var split=new SplitPane(documents,body);split.setDividerPositions(.28);dialog.getDialogPane().setContent(split);
        var bounds=(owner==null?Screen.getPrimary():Screen.getScreensForRectangle(owner.getX(),owner.getY(),Math.max(1,owner.getWidth()),Math.max(1,owner.getHeight())).stream().findFirst().orElse(Screen.getPrimary())).getVisualBounds();
        dialog.getDialogPane().setPrefSize(Math.min(1000,Math.max(600,bounds.getWidth()-48)),Math.min(760,Math.max(420,bounds.getHeight()-80)));
        split.widthProperty().addListener((o,a,b)->{var orientation=b.doubleValue()<700?Orientation.VERTICAL:Orientation.HORIZONTAL;if(split.getOrientation()!=orientation){split.setOrientation(orientation);split.setDividerPositions(.28);}});
        open.setId("gs1AgreementOpenPdf");save.setId("gs1AgreementSavePdf");sign.setId("gs1AgreementSign");check.setId("gs1AgreementReconcile");
        previous.setId("gs1AgreementPreviousPage");next.setId("gs1AgreementNextPage");
        open.setOnAction(event->openPdf());save.setOnAction(event->savePdf());sign.setOnAction(event->sign());check.setOnAction(event->reconcile());
        previous.setOnAction(event->renderPage(page-1));next.setOnAction(event->renderPage(page+1));consent.selectedProperty().addListener((o,a,b)->buttons());
        documents.getSelectionModel().selectedItemProperty().addListener((o,a,b)->select(b));
        dialog.setOnCloseRequest(event->{if(mutation)event.consume();});
        dialog.setOnHidden(event->{disposed=true;selection++;if(!mutation)releasePrepared();image.setImage(null);consent.setSelected(false);});
        if(!entries.isEmpty())documents.getSelectionModel().selectFirst();else status.setText(tr("gs1.agreements.none"));buttons();
    }
    static void show(Window owner,List<OperatorAgreement> entries,Operations operations,Consumer<Gs1AgreementService.Result> changed){
        new Gs1AgreementDialog(owner,entries,operations,changed).dialog.showAndWait();
    }
    private void select(OperatorAgreement document){
        selection++;releasePrepared();pdfVisible=false;page=pages=0;image.setImage(null);consent.setSelected(false);
        title.setText(document==null?tr("gs1.agreements.none"):document.title());title.setTooltip(document==null?null:new Tooltip(document.title()+"\n"+document.createdAt()));title.setMinHeight(Region.USE_PREF_SIZE);status.setText(document==null?tr("gs1.agreements.none"):state(document));pageLabel.setText("");buttons();
    }
    private void openPdf(){
        OperatorAgreement selected=documents.getSelectionModel().getSelectedItem();if(selected==null||working)return;
        long expected=selection;consent.setSelected(false);pdfVisible=false;releasePrepared();image.setImage(null);page=pages=0;pageLabel.setText("");buttons();
        run(()->{
            operations.requireCurrent();
            if(selected.awaitingSignature()){
                var document=operations.prepare(selected);return new Opened(document,document.pdf(),document.digest());
            }
            byte[] bytes=operations.pdf(selected);return new Opened(null,bytes,digest(bytes));
        },value->{
            if(expected!=selection||value.prepared()!=null&&!selected.id().equals(value.prepared().agreement().id())){value.close();return;}
            prepared=value.prepared();viewedPdf=value.pdf();viewedDigest=value.digest();
            if(prepared!=null){
                if(prepared.requiresReconciliation())submittedRequests.put(selected.id(),prepared.requestId());
                // Only a fresh durable DRAFT proof can release an unsent signing attempt.
                // Errors and uncertain submissions remain blocked until read-only reconciliation.
                else submittedRequests.remove(selected.id());
            }
            renderPage(0);
        },failure->{});
    }
    private record Opened(Gs1AgreementService.Prepared prepared,byte[] pdf,String digest)implements AutoCloseable{
        @Override public void close(){if(prepared!=null)prepared.close();Arrays.fill(pdf,(byte)0);}
    }
    private record Page(byte[] png,int index,int count){}
    private static String digest(byte[] pdf)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));}
    private void renderPage(int index){
        if(viewedPdf==null||working||index<0||pages>0&&index>=pages)return;
        byte[] current=viewedPdf;String expectedDigest=viewedDigest;long expected=selection;consent.setSelected(false);pdfVisible=false;buttons();
        run(()->{
            operations.requireCurrent();byte[] pdf=current.clone();
            try{
                String digest=digest(pdf);
                if(!digest.equalsIgnoreCase(expectedDigest))throw new IOException("gs1.agreements.changed");
                try(var document=Loader.loadPDF(pdf)){
                    Gs1PdfResourceGuard.requireSafe(document,index);
                    int count=document.getNumberOfPages();if(document.isEncrypted()||count<1||count>200||index>=count)throw new IOException("gs1.agreements.error");
                    var bounds=document.getPage(index).getCropBox();double width=bounds.getWidth(),height=bounds.getHeight();
                    if(!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0||width>20000||height>20000)throw new IOException("gs1.agreements.error");
                    float scale=(float)Math.min(2,Math.min(1000/width,1800/height));
                    var renderer=new PDFRenderer(document);renderer.setSubsamplingAllowed(true);
                    var rendered=renderer.renderImage(index,scale,ImageType.RGB);var bytes=new ByteArrayOutputStream();
                    if(!ImageIO.write(rendered,"png",bytes))throw new IOException("gs1.agreements.error");
                    return new Page(bytes.toByteArray(),index,count);
                }
            }finally{Arrays.fill(pdf,(byte)0);}
        },rendered->{
            if(expected!=selection||viewedPdf!=current)return;
            image.setImage(new Image(new ByteArrayInputStream(rendered.png())));page=rendered.index();pages=rendered.count();pdfVisible=true;
            pageLabel.setText(tr("gs1.agreements.pdf_page").replace("{0}",Integer.toString(page+1)).replace("{1}",Integer.toString(pages)));
            status.setText(state(documents.getSelectionModel().getSelectedItem()));buttons();
        },failure->{pdfVisible=false;consent.setSelected(false);if("gs1.document_resource_limits".equals(failure.getMessage()))status.setText(tr("gs1.document_resource_limits"));});
    }
    private void savePdf(){
        if(viewedPdf==null||!pdfVisible||working)return;byte[] current=viewedPdf;
        var chooser=new FileChooser();chooser.setTitle(tr("gs1.agreements.save_pdf"));chooser.setInitialFileName("operator-agreement-"+documents.getSelectionModel().getSelectedItem().id()+".pdf");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF","*.pdf"));
        var file=chooser.showSaveDialog(dialog.getDialogPane().getScene().getWindow());if(file==null)return;
        run(()->{operations.requireCurrent();byte[] pdf=current.clone();try{Files.write(file.toPath(),pdf);return true;}finally{Arrays.fill(pdf,(byte)0);}},done->status.setText(tr("gs1.saved")),failure->{});
    }
    private void sign(){
        if(working||prepared==null||!pdfVisible||!consent.isSelected()||!prepared.agreement().awaitingSignature()||submittedRequests.containsKey(prepared.agreement().id()))return;
        var current=prepared;submittedRequests.put(current.agreement().id(),current.requestId());consent.setSelected(false);mutation=true;buttons();
        run(()->{operations.requireCurrent();return operations.sign(current,true);},result->{
            replace(result.agreement());changed.accept(result);status.setText(tr(result.state()==Gs1AgreementService.State.CONFIRMED&&!result.agreement().operatorPending()?"gs1.agreements.confirmed":"gs1.agreements.pending"));
        },failure->status.setText(tr("gs1.agreements.unknown")));
    }
    private void reconcile(){
        OperatorAgreement selected=documents.getSelectionModel().getSelectedItem();if(selected==null||working)return;
        String requestId=submittedRequests.get(selected.id());if(requestId==null)return;
        run(()->{operations.requireCurrent();return operations.reconcile(requestId);},result->{replace(result.agreement());changed.accept(result);status.setText(tr(result.state()==Gs1AgreementService.State.CONFIRMED&&!result.agreement().operatorPending()?"gs1.agreements.confirmed":"gs1.agreements.pending"));},failure->status.setText(tr("gs1.agreements.unknown")));
    }
    private void replace(OperatorAgreement value){
        for(int index=0;index<documents.getItems().size();index++)if(documents.getItems().get(index).id().equals(value.id())){
            documents.getItems().set(index,value);documents.getSelectionModel().select(index);break;
        }buttons();
    }
    private <T>void run(Callable<T> work,Consumer<T> success,Consumer<Throwable> failure){
        if(working||disposed)return;working=true;status.setText(tr("gs1.agreements.loading"));buttons();
        var task=new Task<T>(){@Override protected T call()throws Exception{return work.call();}};
        task.setOnSucceeded(event->{
            working=false;mutation=false;if(disposed){if(task.getValue() instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}releasePrepared();return;}
            success.accept(task.getValue());buttons();
        });
        Consumer<Throwable> failed=error->{working=false;mutation=false;if(disposed){releasePrepared();return;}status.setText(tr("gs1.agreements.error"));failure.accept(error);buttons();};
        task.setOnFailed(event->failed.accept(task.getException()));task.setOnCancelled(event->failed.accept(new IOException("gs1.agreements.unknown")));
        AppTaskExecutor.execute(task,failed);
    }
    private void buttons(){
        OperatorAgreement selected=documents.getSelectionModel().getSelectedItem();boolean submitted=selected!=null&&submittedRequests.containsKey(selected.id());
        documents.setDisable(working);open.setDisable(working||selected==null);save.setDisable(working||viewedPdf==null||!pdfVisible);
        consent.setDisable(working||prepared==null||!pdfVisible||selected==null||!selected.awaitingSignature()||submitted);
        sign.setDisable(working||consent.isDisabled()||!consent.isSelected());check.setDisable(working||!submitted);
        previous.setDisable(working||viewedPdf==null||page<=0);next.setDisable(working||viewedPdf==null||page+1>=pages);
        dialog.getDialogPane().lookupButton(closeType).setDisable(mutation);
    }
    private void releasePrepared(){if(prepared!=null){prepared.close();prepared=null;}if(viewedPdf!=null){Arrays.fill(viewedPdf,(byte)0);viewedPdf=null;}viewedDigest="";}
    private static String state(OperatorAgreement document){return tr(document.operatorPending()?"gs1.agreements.pending":document.sellerSigned()?"gs1.agreements.confirmed":document.awaitingSignature()?"gs1.agreements.awaiting":"gs1.agreements.unknown");}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
