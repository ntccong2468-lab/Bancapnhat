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
            column(tr("fbo.column.color"),SyncRunRepository.Item::color);column(tr("fbo.column.size"),SyncRunRepository.Item::size);column("GTIN",SyncRunRepository.Item::gtin);column("Feed ID",row->identifier(row,"feedId"));column(tr("znack.registration.report_card_id"),row->identifier(row,"goodId"));
            column(tr("znack.registration.status"),row->itemState(runs.getValue(),row));column(tr("jobs.step"),row->label("znack.registration.report_step."+row.step(),row.step()));column(tr("jobs.reason"),RegistrationSyncReportDialog::itemReason);
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
                for(var run:reports.list(owner.getId(),0,100))if(Set.of(RegistrationSources.KIND,RegistrationAttemptReports.KIND,"catalog_repair","znack_catalog_read","znack_marketplace").contains(run.kind())) {
                    try{reports.assertOwner(owner,run.id());owned.add(run);}catch(SecurityException oldOwner){/* historical account */}
                }
                var run=owned.stream().filter(r->r.id().equals(requested)).findFirst().orElse(owned.stream().filter(r->RegistrationAttemptReports.KIND.equals(r.kind())).findFirst().orElse(owned.isEmpty()?null:owned.getFirst()));
                if(run==null)return new Page(owned,null,List.of(),Map.of(),List.of());
                var items=reports.items(run.id(),start,300);RegistrationAttemptReports.refreshAll(owner,run);com.vncode.app.integration.znack.registration.CatalogErrorRepairService.refresh(owner,run,items);
                run=reports.find(run.id()).orElseThrow();items=reports.items(run.id(),start,300);
                for(int i=0;i<owned.size();i++)if(owned.get(i).id().equals(run.id()))owned.set(i,run);
                return new Page(owned,run,items,"znack_marketplace".equals(run.kind())?reports.marketplaceCounts(run.id()):reports.counts(run.id()),reports.events(run.id(),null,4));
            }};running=task;
            task.setOnSucceeded(e->{if(closed)return;var page=task.getValue();runs.getItems().setAll(page.runs());runs.setValue(page.run());selected=page.run()==null?"":page.run().id();table.getItems().setAll(page.items());
                summary.setText(page.run()==null?tr("znack.registration.report_empty"):runState(page.run())+" · "+label("znack.registration.report_step."+page.run().step(),page.run().step())+" · "+reasonCode(page.run().error())+"\n"+summary(page.run(),page.counts())+" · "+(page.items().isEmpty()?0:start+1)+"–"+(start+page.items().size())+" / "+page.run().total());
                events.setText(page.events().stream().map(event->event.time()+" · "+label("znack.registration.report_step."+event.step(),event.step())+" · "+label("jobs.status."+event.state(),event.state())+(event.error().isBlank()?"":" · "+reasonCode(event.error()))).collect(java.util.stream.Collectors.joining("\n")));
                busy=false;buttons();next.setDisable(page.items().size()<300);timer.playFromStart();
            });
            java.util.function.Consumer<Throwable> failed=error->{if(closed)return;busy=false;table.getItems().clear();summary.setText(SyncFailure.of(error,0).code());buttons();};
            task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
        }
        private void buttons(){refresh.setDisable(busy);runs.setDisable(busy);previous.setDisable(busy||offset==0);next.setDisable(busy||table.getItems().size()<300);}
        private ListCell<SyncRunRepository.Run> runCell(){return new ListCell<>(){protected void updateItem(SyncRunRepository.Run run,boolean empty){super.updateItem(run,empty);setText(empty||run==null?"":run.created()+" · "+label("znack.registration.report_kind."+run.kind(),run.kind())+" · "+runState(run));}};}
        private void column(String text,Function<SyncRunRepository.Item,String> value){var column=new TableColumn<SyncRunRepository.Item,String>(text);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));table.getColumns().add(column);}
        void dispose(){closed=true;timer.stop();if(running!=null)running.cancel(true);}
    }
    static String summary(SyncRunRepository.Run run,Map<String,Long> counts){
        if("znack_marketplace".equals(run.kind()))return java.text.MessageFormat.format(tr("gtin.background.report_summary"),counts.getOrDefault("UPDATED",0L),counts.getOrDefault("WITH_GTIN",0L),counts.getOrDefault("NOT_UPDATED",0L),counts.getOrDefault("WITHOUT_GTIN",0L),counts.getOrDefault("BLOCKED",0L));
        if(RegistrationAttemptReports.KIND.equals(run.kind()))return java.text.MessageFormat.format(tr("znack.registration.batch_summary"),run.total(),counts.getOrDefault("QUEUED",0L),counts.getOrDefault("RUNNING",0L),counts.getOrDefault("WAITING_REMOTE",0L),counts.getOrDefault("SUCCEEDED",0L),counts.getOrDefault("NEEDS_ACTION",0L)+counts.getOrDefault("FAILED",0L),counts.getOrDefault("CANCELLED",0L));
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->label("jobs.status."+e.getKey(),e.getKey())+": "+e.getValue()).collect(java.util.stream.Collectors.joining(" · "));
    }
    static String itemReason(SyncRunRepository.Item row){
        String code=row.error();
        String message=reasonCode(code);
        String detail=identifier(row,"reason");
        return message+(detail.isBlank()||detail.equals(code)?"":" · "+detail);
    }
    private static String reasonCode(String code){
        if(code.isBlank())return "";String message=label("znack.registration.reason."+code,FriendlyErrorService.format(new IllegalArgumentException(code)));
        return message+(message.contains("["+code+"]")?"":" ["+code+"]");
    }
    private static String identifier(SyncRunRepository.Item row,String name){
        try{var object=com.google.gson.JsonParser.parseString(row.evidence()).getAsJsonObject();return object.has(name)&&!object.get(name).isJsonNull()?object.get(name).getAsString():"";}catch(RuntimeException invalid){return "";}
    }
    private static String runState(SyncRunRepository.Run run){
        if("znack_marketplace".equals(run.kind()))return tr("gtin.background.report_title");
        if(RegistrationSources.KIND.equals(run.kind())&&"SUCCEEDED".equals(run.state()))return tr("znack.registration.report_sources_only");
        if(RegistrationAttemptReports.KIND.equals(run.kind())&&"SUCCEEDED".equals(run.state()))return tr("znack.registration.report_published");
        return label("jobs.status."+run.state(),run.state());
    }
    private static String itemState(SyncRunRepository.Run run,SyncRunRepository.Item item){
        if(run!=null&&"znack_marketplace".equals(run.kind())&&("true".equals(identifier(item,"marketplaceVerified"))||Set.of("SUCCEEDED","UNCHANGED").contains(item.state())))return tr("gtin.background.result");
        if(run!=null&&RegistrationSources.KIND.equals(run.kind())&&"SUCCEEDED".equals(item.state()))return tr("znack.registration.report_product_read");
        if(run!=null&&RegistrationAttemptReports.KIND.equals(run.kind())&&"SUCCEEDED".equals(item.state()))return tr("znack.registration.report_published");
        return label("jobs.status."+item.state(),item.state());
    }
    private static String label(String key,String fallback){String value=tr(key);return value.equals(key)?fallback:value;}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
