package com.vncode.app.ui.znackregistration;

import com.google.gson.*;
import com.vncode.app.integration.znack.registration.CatalogAutomaticRepairLedger;
import com.vncode.app.integration.znack.registration.CatalogFeedback;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local history of automatic proposals. Reserving a correction is not a Catalog acknowledgement. */
final class RegistrationRepairHistoryDialog {
    record Row(String when,String field,String before,String after,String source,String rejection){}
    static void show(Window owner,Shop shop,String gtin,long goodId){
        var dialog=new Dialog<Void>();if(owner!=null)dialog.initOwner(owner);dialog.initModality(Modality.NONE);dialog.setTitle(tr("znack.repair.history.title"));
        var status=new Label(tr("znack.repair.history.loading"));status.setWrapText(true);
        var hint=new Label(tr("znack.repair.history.hint"));hint.setWrapText(true);
        var table=new TableView<Row>();table.setId("catalogRepairHistoryTable");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,tr("znack.repair.history.when"),Row::when,150);column(table,tr("repair.field"),Row::field,130);column(table,tr("edit.before"),Row::before,170);
        column(table,tr("repair.suggestion"),Row::after,170);column(table,tr("znack.provenance.source"),Row::source,150);column(table,tr("repair.reason"),Row::rejection,220);
        var content=new VBox(10,hint,status,table);content.setPadding(new Insets(16));VBox.setVgrow(table,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(1040,480);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);DialogLayout.install(dialog.getDialogPane());
        var closed=new AtomicBoolean();var task=new Task<List<Row>>(){@Override protected List<Row> call(){return rows(CatalogAutomaticRepairLedger.history(shop,gtin,goodId));}};
        task.setOnSucceeded(e->{if(!closed.get()){table.getItems().setAll(task.getValue());status.setText(task.getValue().isEmpty()?tr("znack.repair.history.empty"):"");}});
        java.util.function.Consumer<Throwable> failed=error->{if(!closed.get())status.setText(tr("znack.repair.history.failed"));};task.setOnFailed(e->failed.accept(task.getException()));task.setOnCancelled(e->failed.accept(null));dialog.setOnHidden(e->{closed.set(true);task.cancel(true);});dialog.show();
        AppTaskExecutor.execute(task,error->Platform.runLater(()->failed.accept(error)));
    }
    static List<Row> rows(List<CatalogAutomaticRepairLedger.Round> rounds){
        var result=new ArrayList<Row>();
        for(var round:rounds){
            JsonArray changes;
            try{changes=JsonParser.parseString(round.changesJson()).getAsJsonArray();}catch(RuntimeException invalid){throw new IllegalStateException("CATALOG_REPAIR_HISTORY_INVALID");}
            for(var element:changes){if(!element.isJsonObject())throw new IllegalStateException("CATALOG_REPAIR_HISTORY_INVALID");var item=element.getAsJsonObject();
                result.add(new Row(safe(round.createdAt()),safe(text(item,"field")),safe(text(item,"before")),safe(text(item,"after")),safe(text(item,"source")),safe(round.rejection())));
            }
        }return List.copyOf(result);
    }
    private static String text(JsonObject object,String field){var value=object.get(field);return value!=null&&value.isJsonPrimitive()?value.getAsString():"";}
    private static String safe(String text){return CatalogFeedback.display(Objects.toString(text,""));}
    private static void column(TableView<Row> table,String title,java.util.function.Function<Row,String> value,double width){var column=new TableColumn<Row,String>(title);column.setCellValueFactory(cell->new ReadOnlyStringWrapper(value.apply(cell.getValue())));column.setPrefWidth(width);RegistrationTextCells.wrap(column);table.getColumns().add(column);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    private RegistrationRepairHistoryDialog(){}
}
