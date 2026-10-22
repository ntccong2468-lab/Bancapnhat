package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/** Edits the registration permit, not an unrelated marketplace document attachment. */
final class RegistrationDocumentConfigDialog {
    static void show(Window window,Shop shop,Runnable saved){
        var owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        var read=new Task<ZnackModels.Settings>(){protected ZnackModels.Settings call(){ListingIdentity.require(owner);return repository(owner).getSettings();}};
        read.setOnSucceeded(e->show(window,owner,read.getValue(),saved));
        read.setOnFailed(e->AlertService.showError(FriendlyErrorService.format(read.getException())));
        AppTaskExecutor.execute(read,error->AlertService.showError(FriendlyErrorService.format(error)));
    }
    static void show(Window window,Shop owner,ZnackModels.Settings settings,Runnable saved){
        var dialog=new Dialog<ZnackModels.GoodsDocument>();if(window!=null)dialog.initOwner(window);AlertService.applyTheme(dialog);
        dialog.setTitle(tr("znack.registration.config"));
        var type=new ComboBox<String>();type.getItems().setAll("CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE");
        type.setValue(java.util.Set.of("1","CONFORMITY_CERTIFICATE","CERTIFICATE","CERTIFICATE_OF_CONFORMITY","СЕРТИФИКАТ").contains(text(settings.documentType()).toUpperCase(java.util.Locale.ROOT))?"CONFORMITY_CERTIFICATE":"CONFORMITY_DECLARATION");
        type.setConverter(new javafx.util.StringConverter<>(){public String toString(String value){return tr("CONFORMITY_CERTIFICATE".equals(value)?"znack.registration.certificate":"znack.registration.declaration");}public String fromString(String value){throw new UnsupportedOperationException();}});
        var number=new TextField(text(settings.documentNumber()));number.setId("registrationConfiguredDocumentNumber");
        var date=new TextField(text(settings.documentDate()));date.setPromptText("dd.MM.yyyy / yyyy-MM-dd");date.setId("registrationConfiguredDocumentDate");
        var note=new Label(tr("znack.registration.config_note"));note.setWrapText(true);note.setMaxWidth(560);
        var grid=new GridPane();grid.setHgap(12);grid.setVgap(12);
        grid.addRow(0,new Label(tr("znack.registration.document_type")),type);grid.addRow(1,new Label(tr("znack.registration.document_number")),number);grid.addRow(2,new Label(tr("znack.registration.document_date")),date);grid.add(note,0,3,2,1);
        dialog.getDialogPane().setContent(grid);var apply=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(apply).addEventFilter(javafx.event.ActionEvent.ACTION,e->{try{
            var updated=settings.withDefaultGoodsDocument(type.getValue(),number.getText().strip(),date.getText().strip());
            updated.validateDefaultGoodsDocument();if(!updated.hasDefaultGoodsDocument())throw new IllegalArgumentException(tr("znack.registration.config_required"));
        }catch(IllegalArgumentException invalid){e.consume();note.setText(FriendlyErrorService.format(invalid));}});
        dialog.setResultConverter(button->button==apply?new ZnackModels.GoodsDocument(type.getValue(),number.getText().strip(),date.getText().strip()):null);
        dialog.showAndWait().ifPresent(document->{
            var write=new Task<Void>(){protected Void call(){ListingIdentity.require(owner);var target=repository(owner);var current=target.getSettings();target.saveSettings(current.withDefaultGoodsDocument(document.type(),document.number(),document.date()));return null;}};
            write.setOnSucceeded(e->saved.run());write.setOnFailed(e->AlertService.showError(FriendlyErrorService.format(write.getException())));
            AppTaskExecutor.execute(write,error->AlertService.showError(FriendlyErrorService.format(error)));
        });
    }
    private static ZnackRepository repository(Shop owner){return new ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName()));}
    private static String text(String value){return value==null?"":value;}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
