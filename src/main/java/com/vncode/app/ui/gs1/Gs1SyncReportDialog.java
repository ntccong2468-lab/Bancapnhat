package com.vncode.app.ui.gs1;

import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;
import java.util.*;
import java.util.function.Function;

/** Read-only GS1 source progress. Observing a report never signs or resends anything. */
final class Gs1SyncReportDialog {
    static void show(Window window,Shop selected){
        if(selected==null)return;var shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        var dialog=new Dialog<Void>();if(window!=null)dialog.initOwner(window);AlertService.applyTheme(dialog);dialog.setTitle(tr("gs1.sync.report")+" · "+shop.getName());
        var report=new Report(shop);dialog.getDialogPane().setContent(report);dialog.getDialogPane().setPrefSize(920,580);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.setOnHidden(e->report.dispose());dialog.show();
    }
    static final class Report extends VBox {
        private final Shop shop;private final SyncRunRepository reports=new SyncRunRepository();
        private final ComboBox<SyncRunRepository.Run> runs=new ComboBox<>();
        private final TableView<SyncRunRepository.Item> table=new TableView<>();
        private final Label status=new Label(),events=new Label();private final Button refresh=new Button(tr("gs1.check_all"));
        private final PauseTransition timer=new PauseTransition(Duration.seconds(4));
        private boolean busy,closed;private String run="";private Task<?> task;
        Report(Shop shop){
            this.shop=shop;setId("gs1SyncReport");setSpacing(10);status.setWrapText(true);events.setWrapText(true);runs.setMaxWidth(Double.MAX_VALUE);
            runs.setCellFactory(v->cell());runs.setButtonCell(cell());table.setId("gs1SyncReportSources");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            column(tr("gs1.sync.source"),r->r.productId().equals("CATALOG")?"National Catalog":"Честный знак");column(tr("znack.registration.status"),r->tr("jobs.status."+r.state()));column(tr("jobs.step"),r->step(r.step()));column(tr("jobs.reason"),SyncRunRepository.Item::error);
            getChildren().setAll(new HBox(10,runs,refresh),status,table,events);HBox.setHgrow(runs,Priority.ALWAYS);VBox.setVgrow(table,Priority.ALWAYS);
            refresh.setText(tr("gs1.sync.refresh_report"));refresh.setOnAction(e->load());runs.setOnAction(e->{if(!busy){run=runs.getValue()==null?"":runs.getValue().id();load();}});timer.setOnFinished(e->load());load();
        }
        private record Page(List<SyncRunRepository.Run> runs,SyncRunRepository.Run run,List<SyncRunRepository.Item> items,List<SyncRunRepository.Event> events){}
        private void load(){
            if(busy||closed)return;busy=true;timer.stop();buttons();String selected=run;
            var read=new Task<Page>(){protected Page call(){
                ListingIdentity.require(shop);var owned=new ArrayList<SyncRunRepository.Run>();for(var candidate:reports.list(shop.getId(),0,100))if(candidate.kind().equals("gs1"))try{reports.assertOwner(shop,candidate.id());owned.add(candidate);}catch(SecurityException oldOwner){}
                var active=owned.stream().filter(r->r.id().equals(selected)).findFirst().orElse(owned.isEmpty()?null:owned.getFirst());
                return new Page(owned,active,active==null?List.of():reports.items(active.id(),0,300),active==null?List.of():reports.events(active.id(),null,10));
            }};task=read;
            read.setOnSucceeded(e->{if(closed)return;var result=read.getValue();runs.getItems().setAll(result.runs());runs.setValue(result.run());run=result.run()==null?"":result.run().id();table.getItems().setAll(result.items());
                status.setText(result.run()==null?tr("gs1.sync.no_report"):tr("jobs.status."+result.run().state())+" · "+step(result.run().step())+" · "+result.run().error());
                events.setText(result.events().stream().map(v->v.time()+" · "+step(v.step())+" · "+tr("jobs.status."+v.state())+(v.error().isBlank()?"":" · "+v.error())).collect(java.util.stream.Collectors.joining("\n")));busy=false;buttons();timer.playFromStart();
            });
            java.util.function.Consumer<Throwable> failed=error->{if(!closed){busy=false;table.getItems().clear();status.setText(SyncFailure.of(error,0).code());buttons();}};
            read.setOnFailed(e->failed.accept(read.getException()));AppTaskExecutor.execute(read,failed);
        }
        private ListCell<SyncRunRepository.Run> cell(){return new ListCell<>(){protected void updateItem(SyncRunRepository.Run value,boolean empty){super.updateItem(value,empty);setText(empty||value==null?null:value.created()+" · "+tr("jobs.status."+value.state()));}};}
        private void column(String label,Function<SyncRunRepository.Item,String> value){var column=new TableColumn<SyncRunRepository.Item,String>(label);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));table.getColumns().add(column);}
        private void buttons(){refresh.setDisable(busy);runs.setDisable(busy);}
        void dispose(){closed=true;timer.stop();if(task!=null)task.cancel(true);}
    }
    private static String step(String step){String key="gs1.sync.step."+step;String value=tr(key);return value.equals(key)?step:value;}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
