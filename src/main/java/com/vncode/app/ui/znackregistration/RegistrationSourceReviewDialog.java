package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.*;

/** One batch review: facts remain unverified until the seller explicitly accepts them. */
final class RegistrationSourceReviewDialog {
    private record Row(RegistrationDynamicPreflight.Plan plan,RegistrationFieldProvenance.Evidence evidence){}
    static RegistrationSourceConsent.Prepared show(Window owner,Shop selected,UUID intent,RegistrationDynamicPreflight.Review review,Map<ProductKey,RegistrationSourceConsent.Target> targets){
        if(selected==null||review==null||!review.blocked().isEmpty()||!review.missing().isEmpty()||!review.documents().isEmpty())return null;
        Shop shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        var dialog=new Dialog<RegistrationSourceConsent.Prepared>();dialog.setResultConverter(type->null);if(owner!=null)dialog.initOwner(owner);dialog.setTitle(tr("znack.source_review.title"));
        var table=new TableView<Row>();table.setId("registrationSourceReviewTable");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // Thumbnail visibility does not prove that a seller reviewed an image.
        // No image is approved in this review; CREATE must omit optional images.
        var seenImages=new HashMap<ProductKey,Set<String>>();
        column(table,tr("products.article"),r->RegistrationProvenanceDialog.product(r.plan()),150);
        column(table,tr("znack.source_review.target"),r->{var target=targets.get(r.plan().snapshot().key());
            return target!=null&&target.action()==RegistrationSourceConsent.Action.REPLACE
                    ?tr("znack.source_review.replacing")+" · GTIN "+target.gtin()+" · good_id "+target.goodId():"";},230);
        boolean replacing=targets.values().stream().anyMatch(target->target.action()==RegistrationSourceConsent.Action.REPLACE);
        if(replacing)column(table,tr("znack.source_review.replacement_identity"),r->{var target=targets.get(r.plan().snapshot().key());return target!=null&&target.action()==RegistrationSourceConsent.Action.REPLACE?"GTIN: "+target.gtin()+" · good_id: "+target.goodId():"";},220);
        column(table,tr("znack.registration.complete_missing_field"),r->RegistrationProvenanceDialog.fieldName(r.plan(),r.evidence().field()),170);
        column(table,tr("znack.registration.complete_missing_value"),r->RegistrationProvenanceDialog.value(r.plan(),r.evidence().field()),230);
        column(table,tr("znack.provenance.source"),r->{var source=RegistrationProvenanceDialog.matchesEvidence(r.plan(),r.evidence())?r.evidence().source():RegistrationFieldProvenance.Source.UNKNOWN;return tr("znack.provenance.source."+source.name().toLowerCase(Locale.ROOT));},180);
        var info=new Label(tr("znack.source_review.hint"));info.setWrapText(true);var status=new Label(tr("znack.source_review.loading"));status.setWrapText(true);
        var confirmValues=new CheckBox(tr("znack.source_review.confirm_values"));confirmValues.setWrapText(true);confirmValues.setId("registrationSourceConfirm");
        var confirmReplacement=new CheckBox(tr("znack.source_review.confirm_replacement"));confirmReplacement.setWrapText(true);confirmReplacement.setId("registrationSourceConfirmReplacement");confirmReplacement.setVisible(replacing);confirmReplacement.setManaged(replacing);
        var repair=new CheckBox(tr("znack.source_review.repair_opt_in"));repair.setWrapText(true);repair.setId("registrationSourceRepairOptIn");
        var content=new VBox(9,info,status,table,confirmValues,confirmReplacement,repair);content.setPadding(new Insets(14));VBox.setVgrow(table,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(1020,620);DialogLayout.install(dialog.getDialogPane());
        var apply=new ButtonType(tr("znack.source_review.confirm"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,new ButtonType(tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE));
        var button=(Button)dialog.getDialogPane().lookupButton(apply);button.setId("registrationSourceReviewApply");button.setDisable(true);
        var disposed=new java.util.concurrent.atomic.AtomicBoolean();var loading=new java.util.concurrent.atomic.AtomicBoolean(true);
        Task<List<Row>> read=new Task<>(){@Override protected List<Row> call(){var rows=new ArrayList<Row>();var evidenceByKey=RegistrationFieldProvenance.readBatch(shop,review.plans());for(var plan:review.plans()){
            if(isCancelled())throw new java.util.concurrent.CancellationException("ZNACK_SYNC_CANCELLED");
            var facts=evidenceByKey.getOrDefault(plan.snapshot().key(),List.of());if(facts.isEmpty())throw new IllegalStateException("ZNACK_SOURCE_REVIEW_REQUIRED");
            for(var fact:facts)rows.add(new Row(plan,fact));
        }return List.copyOf(rows);}};
        read.setOnSucceeded(e->{if(disposed.get())return;table.getItems().setAll(read.getValue());loading.set(false);status.setText("");button.setDisable(!confirmValues.isSelected()||replacing&&!confirmReplacement.isSelected()||read.getValue().isEmpty());});
        java.util.function.Consumer<Throwable> readFailed=error->{if(!disposed.get()){loading.set(false);status.setText(tr("znack.source_review.failed"));button.setDisable(true);}};
        read.setOnFailed(e->readFailed.accept(read.getException()));read.setOnCancelled(e->readFailed.accept(null));
        Runnable refreshButton=()->button.setDisable(!confirmValues.isSelected()||replacing&&!confirmReplacement.isSelected()||loading.get()||table.getItems().isEmpty());
        confirmValues.selectedProperty().addListener((o,a,b)->refreshButton.run());confirmReplacement.selectedProperty().addListener((o,a,b)->refreshButton.run());
        final Task<?>[] preparing={null};
        button.addEventFilter(javafx.event.ActionEvent.ACTION,event->{event.consume();if(loading.get()||!confirmValues.isSelected()||replacing&&!confirmReplacement.isSelected()||table.getItems().isEmpty())return;
            button.setDisable(true);confirmValues.setDisable(true);confirmReplacement.setDisable(true);repair.setDisable(true);status.setText(tr("znack.source_review.preparing"));
            var images=new HashMap<ProductKey,Set<String>>();seenImages.forEach((key,value)->images.put(key,Set.copyOf(value)));boolean repairAccepted=repair.isSelected();
            var facts=new LinkedHashMap<ProductKey,List<RegistrationFieldProvenance.Evidence>>();for(var row:table.getItems())facts.computeIfAbsent(row.plan().snapshot().key(),ignored->new ArrayList<>()).add(row.evidence());
            var frozenFacts=new LinkedHashMap<ProductKey,List<RegistrationFieldProvenance.Evidence>>();facts.forEach((key,value)->frozenFacts.put(key,List.copyOf(value)));
            var task=new Task<RegistrationSourceConsent.Prepared>(){@Override protected RegistrationSourceConsent.Prepared call(){return RegistrationSourceConsent.prepare(shop,intent,review.plans(),targets,Map.copyOf(images),Map.copyOf(frozenFacts),repairAccepted);}};preparing[0]=task;
            task.setOnSucceeded(e->{if(!disposed.get()){dialog.setResult(task.getValue());dialog.close();}});
            java.util.function.Consumer<Throwable> failed=error->{if(!disposed.get()){status.setText(tr("znack.source_review.failed"));confirmValues.setDisable(false);confirmReplacement.setDisable(false);repair.setDisable(false);refreshButton.run();}};
            task.setOnFailed(e->failed.accept(task.getException()));task.setOnCancelled(e->failed.accept(null));AppTaskExecutor.executeCatalog(task,error->Platform.runLater(()->failed.accept(error)));
        });
        dialog.setOnShown(e->AppTaskExecutor.executeCatalog(read,error->Platform.runLater(()->readFailed.accept(error))));
        dialog.setOnHidden(e->{disposed.set(true);read.cancel(true);if(preparing[0]!=null)preparing[0].cancel(true);});
        return dialog.showAndWait().orElse(null);
    }
    private static void column(TableView<Row> table,String name,java.util.function.Function<Row,String> value,double width){var c=new TableColumn<Row,String>(name);c.setCellValueFactory(r->new ReadOnlyStringWrapper(Objects.toString(value.apply(r.getValue()),"")));c.setPrefWidth(width);RegistrationTextCells.wrap(c);table.getColumns().add(c);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    private RegistrationSourceReviewDialog(){}
}
