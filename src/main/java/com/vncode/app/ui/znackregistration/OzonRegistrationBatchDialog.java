package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.ProductJson;
import com.vncode.app.integration.znack.registration.OzonCardRegistrationWorkflow;
import com.vncode.app.shared.I18nService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.Set;

public final class OzonRegistrationBatchDialog {
    private OzonRegistrationBatchDialog() {}
    public static List<OzonCardRegistrationWorkflow.BatchItem> review(OzonCardRegistrationWorkflow.BatchReview review) {
        var dialog=new Dialog<List<OzonCardRegistrationWorkflow.BatchItem>>();dialog.setTitle(tr("znack.registration.batch_title"));var summary=new Label();summary.setWrapText(true);
        var table=new TableView<OzonCardRegistrationWorkflow.BatchItem>();table.getItems().setAll(review.items());
        var approved=new java.util.LinkedHashSet<com.vncode.app.features.products.ProductKey>();review.ready().forEach(i->approved.add(i.key()));
        var submit=new ButtonType(tr("znack.registration.batch_submit"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(submit,ButtonType.CANCEL);
        Runnable updateSummary=()->{summary.setText(java.text.MessageFormat.format(tr("znack.registration.batch_confirm"),approved.size(),table.getItems().size()));dialog.getDialogPane().lookupButton(submit).setDisable(approved.isEmpty());};updateSummary.run();
        var selection=new TableColumn<OzonCardRegistrationWorkflow.BatchItem,OzonCardRegistrationWorkflow.BatchItem>(tr("products.select_page"));
        selection.setCellValueFactory(v->new javafx.beans.property.ReadOnlyObjectWrapper<>(v.getValue()));selection.setCellFactory(c->new TableCell<>(){
            private final CheckBox box=new CheckBox();{box.setOnAction(e->{var i=getItem();if(i!=null){if(box.isSelected())approved.add(i.key());else approved.remove(i.key());updateSummary.run();}});}
            protected void updateItem(OzonCardRegistrationWorkflow.BatchItem i,boolean empty){super.updateItem(i,empty);if(i==null||empty){setGraphic(null);return;}box.setSelected(approved.contains(i.key()));box.setDisable(!i.ready()&&!i.needsDocumentConsent());setGraphic(box);}
        });
        var product=new TableColumn<OzonCardRegistrationWorkflow.BatchItem,String>(tr("fbo.column.name"));product.setCellValueFactory(v->new ReadOnlyStringWrapper(v.getValue().key().cardId()+" · "+(v.getValue().preparation()==null?"":v.getValue().preparation().snapshot().title())));
        var state=new TableColumn<OzonCardRegistrationWorkflow.BatchItem,String>(tr("znack.registration.status"));state.setCellValueFactory(v->new ReadOnlyStringWrapper(tr(v.getValue().ready()?"fbo.preflight.READY":v.getValue().issue())));
        var edit=new TableColumn<OzonCardRegistrationWorkflow.BatchItem,OzonCardRegistrationWorkflow.BatchItem>(tr("znack.registration.action"));edit.setCellValueFactory(row->new javafx.beans.property.ReadOnlyObjectWrapper<>(row.getValue()));edit.setCellFactory(c->new TableCell<>(){
            final Button button=new Button(tr("znack.registration.complete_missing_edit"));{button.setOnAction(e->{var item=getItem();if(item==null)return;var draft=OzonRegistrationReviewDialog.review(item.preparation());if(draft==null)return;int index=table.getItems().indexOf(item);if(index>=0){table.getItems().set(index,new OzonCardRegistrationWorkflow.BatchItem(item.key(),item.preparation(),draft,""));approved.add(item.key());updateSummary.run();}});}
            protected void updateItem(OzonCardRegistrationWorkflow.BatchItem item,boolean empty){super.updateItem(item,empty);boolean editable=!empty&&item!=null&&item.preparation()!=null&&item.preparation().preflight()!=null&&item.preparation().categoryId()>0&&Set.of("znack.registration.batch_needs_review","znack.registration.batch_document_review").contains(item.issue());setGraphic(editable?button:null);}
        });
        table.getColumns().setAll(selection,product,state,edit);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.setPrefHeight(240);
        var detail=new TextArea();detail.setEditable(false);detail.setWrapText(true);detail.setPrefRowCount(7);
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->detail.setText(b==null?"":b.draft()!=null?ProductJson.GSON.toJson(b.draft())+"\n\n"+(b.ready()?"":tr(b.issue())):tr(b.issue())));
        var documentConsent=new CheckBox(tr("znack.registration.batch_document_consent"));documentConsent.setWrapText(true);
        documentConsent.setOnAction(e->{for(var i:table.getItems())if(i.needsDocumentConsent()){if(documentConsent.isSelected())approved.add(i.key());else approved.remove(i.key());}updateSummary.run();table.refresh();});
        dialog.getDialogPane().setContent(new VBox(12,summary,documentConsent,table,detail));dialog.getDialogPane().setPrefWidth(850);
        dialog.getDialogPane().lookupButton(submit).addEventFilter(javafx.event.ActionEvent.ACTION,e->{if(approved.isEmpty())e.consume();});
        dialog.setResultConverter(button->button==submit?table.getItems().stream().filter(i->approved.contains(i.key())).map(i->new OzonCardRegistrationWorkflow.BatchItem(i.key(),i.preparation(),i.draft(),"" )).toList():List.of());com.vncode.app.shared.DialogLayout.install(dialog.getDialogPane());com.vncode.app.shared.AlertService.applyTheme(dialog);return dialog.showAndWait().orElse(List.of());
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
