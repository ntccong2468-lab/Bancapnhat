package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Edits the registration permit, not an unrelated marketplace document attachment. */
public final class RegistrationDocumentConfigDialog {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd.MM.uuuu",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private record ConfiguredDocument(String type,String number,String date,String expiry){}
    public static void show(Window window,Shop shop,Runnable saved){
        var owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        var read=new Task<ZnackModels.Settings>(){protected ZnackModels.Settings call(){ListingIdentity.require(owner);return repository(owner).getSettings();}};
        read.setOnSucceeded(e->show(window,owner,read.getValue(),saved));
        read.setOnFailed(e->AlertService.showError(FriendlyErrorService.format(read.getException())));
        AppTaskExecutor.execute(read,error->AlertService.showError(FriendlyErrorService.format(error)));
    }
    static void show(Window window,Shop owner,ZnackModels.Settings settings,Runnable saved){
        var dialog=new Dialog<ConfiguredDocument>();dialog.getDialogPane().setId("registrationConfiguredDocumentDialog");if(window!=null)dialog.initOwner(window);AlertService.applyTheme(dialog);
        dialog.setTitle(tr("znack.registration.config"));
        var type=new ComboBox<String>();type.getItems().setAll("CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE");
        type.setValue(java.util.Set.of("1","CONFORMITY_CERTIFICATE","CERTIFICATE","CERTIFICATE_OF_CONFORMITY","СЕРТИФИКАТ").contains(text(settings.documentType()).toUpperCase(java.util.Locale.ROOT))?"CONFORMITY_CERTIFICATE":"CONFORMITY_DECLARATION");
        type.setConverter(new javafx.util.StringConverter<>(){public String toString(String value){return tr("CONFORMITY_CERTIFICATE".equals(value)?"znack.registration.certificate":"znack.registration.declaration");}public String fromString(String value){throw new UnsupportedOperationException();}});
        var number=new TextField(text(settings.documentNumber()));number.setId("registrationConfiguredDocumentNumber");
        var date=new TextField(text(settings.documentDate()));date.setPromptText("dd.MM.yyyy / yyyy-MM-dd");date.setId("registrationConfiguredDocumentDate");
        var expiry=new TextField(text(settings.documentExpiryDate()));expiry.setPromptText("dd.MM.yyyy / yyyy-MM-dd");expiry.setId("registrationConfiguredDocumentExpiryDate");
        var originalIdentity=identity(type.getValue(),number.getText(),date.getText());
        var expiryDrafts=new java.util.HashMap<java.util.List<String>,String>();
        expiryDrafts.put(originalIdentity,expiry.getText());
        var previousIdentity=new java.util.concurrent.atomic.AtomicReference<>(originalIdentity);
        var changingExpiry=new java.util.concurrent.atomic.AtomicBoolean();
        expiry.textProperty().addListener((o,a,b)->{if(!changingExpiry.get())expiryDrafts.put(previousIdentity.get(),b);});
        Runnable identityChanged=()->{
            var next=identity(type.getValue(),number.getText(),date.getText());
            if(next.equals(previousIdentity.getAndSet(next)))return;
            changingExpiry.set(true);
            // Keep edited or deliberately empty values only for their own permit identity.
            try{expiry.setText(expiryDrafts.getOrDefault(next,""));}
            finally{changingExpiry.set(false);}
        };
        type.valueProperty().addListener((o,a,b)->identityChanged.run());number.textProperty().addListener((o,a,b)->identityChanged.run());date.textProperty().addListener((o,a,b)->identityChanged.run());
        var note=new Label(tr("znack.registration.config_note"));note.setWrapText(true);note.setMaxWidth(560);
        var expiryNote=new Label(tr("znack.registration.config_expiry_note"));expiryNote.setWrapText(true);expiryNote.setMaxWidth(560);
        var grid=new GridPane();grid.setHgap(12);grid.setVgap(12);
        grid.addRow(0,new Label(tr("znack.registration.document_type")),type);grid.addRow(1,new Label(tr("znack.registration.document_number")),number);grid.addRow(2,new Label(tr("znack.registration.document_date")),date);grid.addRow(3,new Label(tr("znack.document_expiry_date")),expiry);grid.add(expiryNote,0,4,2,1);grid.add(note,0,5,2,1);
        dialog.getDialogPane().setContent(grid);var apply=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);
        var reviewed=new java.util.concurrent.atomic.AtomicReference<ConfiguredDocument>();
        dialog.getDialogPane().lookupButton(apply).addEventFilter(javafx.event.ActionEvent.ACTION,e->{try{
            LocalDate from=parseDate(date.getText(),"znack.registration.invalid_document_date"),until=parseDate(expiry.getText(),"znack.registration.invalid_document_expiry_date");
            if(from!=null&&until!=null&&until.isBefore(from))throw new IllegalArgumentException(tr("znack.registration.document_expiry_before_start"));
            var document=new ConfiguredDocument(type.getValue(),number.getText().strip(),format(from),format(until));
            var updated=settings.withDefaultGoodsDocument(document.type(),document.number(),document.date(),document.expiry());
            if(!updated.hasDefaultGoodsDocument())throw new IllegalArgumentException(tr("znack.registration.config_required"));updated.validateDefaultGoodsDocument();reviewed.set(document);
        }catch(IllegalArgumentException invalid){e.consume();note.setText(FriendlyErrorService.format(invalid));}});
        dialog.setResultConverter(button->button==apply?reviewed.get():null);
        dialog.showAndWait().ifPresent(document->{
            var write=new Task<Void>(){protected Void call(){ListingIdentity.require(owner);repository(owner).saveDefaultGoodsDocument(document.type(),document.number(),document.date(),document.expiry());return null;}};
            write.setOnSucceeded(e->saved.run());write.setOnFailed(e->AlertService.showError(FriendlyErrorService.format(write.getException())));
            AppTaskExecutor.execute(write,error->AlertService.showError(FriendlyErrorService.format(error)));
        });
    }
    private static String format(LocalDate date){return date==null?"":DATE.format(date);}
    private static LocalDate parseDate(String value,String errorKey){
        if(value==null||value.isBlank())return null;
        try{return readDate(value.strip());}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException(tr(errorKey));}
    }
    private static LocalDate readDate(String value){
        try{return LocalDate.parse(value);}catch(java.time.DateTimeException iso){return LocalDate.parse(value,DATE);}
    }
    private static java.util.List<String> identity(String type,String number,String date){
        String issued=text(date).strip();try{if(!issued.isBlank())issued=readDate(issued).toString();}catch(java.time.DateTimeException incomplete){/* Keep partial editor input as identity until a valid date is entered. */}
        return java.util.List.of(text(type),PermitDocumentNumber.normalize(number),issued);
    }
    private static ZnackRepository repository(Shop owner){return new ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName()));}
    private static String text(String value){return value==null?"":value;}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
