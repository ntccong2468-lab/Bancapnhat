package com.vncode.app.ui.znackregistration;

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

/** Bounded, owner-scoped report. No HTTP payload, credentials or KIZ data reach this view. */
final class RegistrationSyncReportDialog {
    static void show(Window window,Shop shop,String initial) {
        var owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        var dialog=new Dialog<Void>();if(window!=null)dialog.initOwner(window);AlertService.applyTheme(dialog);
        dialog.setTitle(tr("znack.registration.report")+" · "+shop.getName());
        var report=new Report(owner,initial);dialog.getDialogPane().setContent(report);dialog.getDialogPane().setPrefSize(1080,640);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.setOnHidden(e->report.dispose());dialog.show();
    }
    static final class Report extends VBox {
        private final Shop owner;
        private final SyncRunRepository reports=new SyncRunRepository();
        private final ComboBox<SyncRunRepository.Run> runs=new ComboBox<>();
        private final TableView<SyncRunRepository.Item> table=new TableView<>();
        private final Label summary=new Label(),events=new Label();
        private final Button previous=new Button("←"),next=new Button("→"),refresh=new Button(tr("znack.registration.refresh"));
        private final PauseTransition timer=new PauseTransition(Duration.seconds(4));
        private String selected;private int offset;private boolean busy,closed;
        private Task<?> running;
        Report(Shop owner,String initial){
            this.owner=owner;selected=initial;setId("registrationSyncReport");setSpacing(8);summary.setWrapText(true);events.setWrapText(true);
            runs.setPrefWidth(650);runs.setCellFactory(list->runCell());runs.setButtonCell(runCell());
            table.setId("registrationSyncReportItems");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            column("Product / variant",row->row.productId()+" / "+row.variantId());column("SKU",SyncRunRepository.Item::sku);column("Article",SyncRunRepository.Item::article);
            column(tr("fbo.column.color"),SyncRunRepository.Item::color);column(tr("fbo.column.size"),SyncRunRepository.Item::size);column("GTIN",SyncRunRepository.Item::gtin);
            column(tr("znack.registration.status"),row->label("jobs.status."+row.state(),row.state()));column(tr("jobs.step"),SyncRunRepository.Item::step);column(tr("jobs.reason"),SyncRunRepository.Item::error);
            getChildren().setAll(new FlowPane(8,8,runs,refresh),summary,table,events,new HBox(8,previous,next));VBox.setVgrow(table,Priority.ALWAYS);
            runs.setOnAction(e->{if(busy)return;var run=runs.getValue();selected=run==null?"":run.id();offset=0;load();});
            previous.setOnAction(e->{offset=Math.max(0,offset-300);load();});next.setOnAction(e->{offset+=300;load();});refresh.setOnAction(e->load());
            timer.setOnFinished(e->load());load();
        }
        private record Page(List<SyncRunRepository.Run> runs,SyncRunRepository.Run run,List<SyncRunRepository.Item> items,Map<String,Long> counts,List<SyncRunRepository.Event> events){}
        private void load(){
            if(closed||busy)return;timer.stop();busy=true;buttons();String requested=selected;int start=offset;
            Task<Page> task=new Task<>(){protected Page call(){
                ListingIdentity.require(owner);var owned=new ArrayList<SyncRunRepository.Run>();
                for(var run:reports.list(owner.getId(),0,100))if(Set.of(RegistrationSources.KIND,"znack_catalog_read","znack_marketplace").contains(run.kind())) {
                    try{reports.assertOwner(owner,run.id());owned.add(run);}catch(SecurityException oldOwner){/* historical account */}
                }
                var run=owned.stream().filter(r->r.id().equals(requested)).findFirst().orElse(owned.isEmpty()?null:owned.getFirst());
                if(run==null)return new Page(owned,null,List.of(),Map.of(),List.of());
                return new Page(owned,run,reports.items(run.id(),start,300),reports.counts(run.id()),reports.events(run.id(),null,4));
            }};running=task;
            task.setOnSucceeded(e->{if(closed)return;var page=task.getValue();runs.getItems().setAll(page.runs());runs.setValue(page.run());selected=page.run()==null?"":page.run().id();table.getItems().setAll(page.items());
                summary.setText(page.run()==null?tr("znack.registration.report_empty"):label("jobs.status."+page.run().state(),page.run().state())+" · "+page.run().step()+" · "+page.run().error()+"\n"+page.counts()+" · "+(page.items().isEmpty()?0:start+1)+"–"+(start+page.items().size())+" / "+page.run().total());
                events.setText(page.events().stream().map(event->event.time()+" · "+event.step()+" · "+event.state()+(event.error().isBlank()?"":" · "+event.error())).collect(java.util.stream.Collectors.joining("\n")));
                busy=false;buttons();next.setDisable(page.items().size()<300);timer.playFromStart();
            });
            java.util.function.Consumer<Throwable> failed=error->{if(closed)return;busy=false;table.getItems().clear();summary.setText(SyncFailure.of(error,0).code());buttons();};
            task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
        }
        private void buttons(){refresh.setDisable(busy);runs.setDisable(busy);previous.setDisable(busy||offset==0);next.setDisable(busy||table.getItems().size()<300);}
        private ListCell<SyncRunRepository.Run> runCell(){return new ListCell<>(){protected void updateItem(SyncRunRepository.Run run,boolean empty){super.updateItem(run,empty);setText(empty||run==null?"":run.created()+" · "+run.kind()+" · "+label("jobs.status."+run.state(),run.state()));}};}
        private void column(String text,Function<SyncRunRepository.Item,String> value){var column=new TableColumn<SyncRunRepository.Item,String>(text);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));table.getColumns().add(column);}
        void dispose(){closed=true;timer.stop();if(running!=null)running.cancel(true);}
    }
    private static String label(String key,String fallback){String value=tr(key);return value.equals(key)?fallback:value;}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
