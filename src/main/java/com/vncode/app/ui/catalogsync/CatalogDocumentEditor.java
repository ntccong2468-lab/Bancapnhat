package com.vncode.app.ui.catalogsync;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.time.Instant;
import java.util.*;

/** The seller supplies missing original files/metadata; no invented expiry or certificate approval. */
final class CatalogDocumentEditor {
    private CatalogDocumentEditor(){}
    static Optional<ProductDocument> show(javafx.stage.Window owner,Marketplace marketplace,ProductDocument original) {
        var dialog=new Dialog<ProductDocument>();AlertService.applyTheme(dialog);dialog.setTitle(tr("catalogsync.document_edit"));dialog.initOwner(owner);dialog.setResizable(true);
        var fields=new LinkedHashMap<String,TextField>();
        for(String key:List.of("number","type","name","certificate_country","accordance_type","product_type","link_to_registry"))fields.put(key,new TextField());
        var from=new DatePicker(original.validFrom());var until=new DatePicker(original.validUntil());DocumentDateInput.configure(from);DocumentDateInput.configure(until);
        var endless=new CheckBox(tr("copy.doc.isEndless"));endless.setSelected("true".equals(original.metadata().get("isEndless")));
        fields.get("number").setText(original.number());fields.get("type").setText(marketplace==Marketplace.OZON?original.metadata().getOrDefault("certificate_type",""):original.type());
        for(var entry:fields.entrySet())if(!Set.of("number","type").contains(entry.getKey()))entry.getValue().setText(original.metadata().getOrDefault(entry.getKey(),""));
        var file=new java.util.concurrent.atomic.AtomicReference<>(original.file());var choose=new Button(tr("edit.file"));
        choose.setOnAction(e->{var picker=new FileChooser();picker.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF / JPEG / PNG","*.pdf","*.jpg","*.jpeg","*.png"));var picked=picker.showOpenDialog(owner);if(picked!=null){file.set(picked.toURI());choose.setText(picked.getName());}});
        var form=new VBox(10);for(var entry:fields.entrySet()){var label=new Label(tr("copy.doc."+entry.getKey()));label.setMinWidth(160);HBox.setHgrow(entry.getValue(),Priority.ALWAYS);form.getChildren().add(new HBox(8,label,entry.getValue()));}
        form.getChildren().addAll(new Label(tr("catalogsync.document_notice")),new HBox(8,from,until,endless),choose);
        var scroll=new ScrollPane(form);scroll.setFitToWidth(true);dialog.getDialogPane().setContent(scroll);dialog.getDialogPane().setPrefSize(740,530);
        var save=new ButtonType(tr("catalogsync.save_document"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        var error=new Label();error.setWrapText(true);form.getChildren().add(error);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION,e->{try{DocumentDateInput.commit(from);if(!endless.isSelected())DocumentDateInput.commit(until);if(fields.get("number").getText().isBlank()||from.getValue()==null||!endless.isSelected()&&until.getValue()==null)throw new IllegalArgumentException();}catch(RuntimeException invalid){error.setText(tr("catalogsync.issue.DOCUMENT_DETAILS_REQUIRED"));e.consume();}});
        dialog.setResultConverter(button->{if(button!=save)return null;var metadata=new LinkedHashMap<>(original.metadata());for(var entry:fields.entrySet())if(!entry.getKey().equals("number")&&!entry.getKey().equals("type"))metadata.put(entry.getKey(),entry.getValue().getText().strip());metadata.put("isEndless",Boolean.toString(endless.isSelected()));if(marketplace==Marketplace.OZON)metadata.put("certificate_type",fields.get("type").getText().strip());return new ProductDocument("",marketplace==Marketplace.OZON?original.type():fields.get("type").getText().strip(),fields.get("number").getText().strip(),from.getValue(),endless.isSelected()?null:until.getValue(),original.owner(),original.scope(),file.get(),marketplace,DocumentState.UNVERIFIED,original.source(),Instant.now(),metadata);});
        return dialog.showAndWait();
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
