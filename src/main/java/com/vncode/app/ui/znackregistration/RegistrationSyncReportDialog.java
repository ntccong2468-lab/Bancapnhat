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
        private final TextField search=new TextField();private final ComboBox<String> state=new ComboBox<>();private final Button export=new Button(tr("jobs.export"));
        private final PauseTransition filterDebounce=new PauseTransition(Duration.millis(250));
        private final PauseTransition timer=new PauseTransition(Duration.seconds(4));
        private String selected;private int offset;private boolean busy,closed,pendingReload,exporting,applyingFilters;private long matched;
        private Task<?> running,exportTask;
        Report(Shop owner,String initial){
            this.owner=owner;selected=initial;setId("registrationSyncReport");setSpacing(8);summary.setWrapText(true);events.setWrapText(true);
            runs.setPrefWidth(650);runs.setCellFactory(list->runCell());runs.setButtonCell(runCell());
            search.setId("registrationSyncReportSearch");search.setPromptText(tr("znack.registration.report_search"));search.setPrefWidth(450);state.setId("registrationSyncReportState");state.getItems().setAll("ALL");state.setValue("ALL");state.setConverter(new javafx.util.StringConverter<>(){public String toString(String value){return "ALL".equals(value)?tr("znack.registration.filter.all"):label("jobs.status."+value,value);}public String fromString(String text){throw new UnsupportedOperationException();}});export.setId("registrationSyncReportExport");
            table.setId("registrationSyncReportItems");table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            column("Marketplace",row->owner.getMarketplace().name());column(tr("jobs.shop"),row->owner.getName());
            column("Product / variant",row->row.productId()+" / "+row.variantId());column("SKU",SyncRunRepository.Item::sku);column("Article",SyncRunRepository.Item::article);
            column(tr("jobs.reason"),RegistrationSyncReportDialog::itemReason);
            column(tr("fbo.column.color"),SyncRunRepository.Item::color);column(tr("fbo.column.size"),SyncRunRepository.Item::size);column("GTIN",SyncRunRepository.Item::gtin);column("Feed ID",row->identifier(row,"feedId"));column(tr("znack.registration.report_card_id"),row->identifier(row,"goodId"));
            column(tr("znack.registration.report_barcode"),row->RegistrationReportData.field(row,"barcodes"));column(tr("znack.registration.report_catalog_state"),RegistrationReportData::catalogState);column(tr("znack.registration.report_catalog_checked"),row->RegistrationReportData.field(row,"catalogCheckedAt"));column(tr("znack.registration.report_check_result"),row->result(row,"checkResult"));column(tr("znack.registration.report_sync_result"),row->result(row,"syncResult"));column(tr("znack.registration.report_updated"),SyncRunRepository.Item::updatedAt);column(tr("znack.registration.report_next_action"),RegistrationSyncReportDialog::nextAction);
            column(tr("znack.registration.status"),row->itemState(runs.getValue(),row));column(tr("jobs.step"),row->label("znack.registration.report_step."+row.step(),row.step()));
            getChildren().setAll(new FlowPane(8,8,runs,refresh),new FlowPane(8,8,search,state,export),summary,table,events,new HBox(8,previous,next));VBox.setVgrow(table,Priority.ALWAYS);
            runs.setOnAction(e->{if(busy)return;var run=runs.getValue();selected=run==null?"":run.id();offset=0;load();});
            previous.setOnAction(e->{offset=Math.max(0,offset-300);load();});next.setOnAction(e->{offset+=300;load();});refresh.setOnAction(e->load());
            filterDebounce.setOnFinished(e->{offset=0;load();});search.textProperty().addListener((o,a,b)->filterDebounce.playFromStart());state.valueProperty().addListener((o,before,current)->{if(!applyingFilters){offset=0;load();}});export.setOnAction(e->export());
            timer.setOnFinished(e->load());load();
        }
        private record Page(List<SyncRunRepository.Run> runs,SyncRunRepository.Run run,List<SyncRunRepository.Item> items,Map<String,Long> counts,List<SyncRunRepository.Event> events,long matched,Set<String> states,String scopeKey,List<SyncRunRepository.Item> failures){}
        private void load(){
            if(closed)return;if(busy){pendingReload=true;return;}timer.stop();busy=true;buttons();String requested=selected;int start=offset;var filter=filter();
            Task<Page> task=new Task<>(){protected Page call(){
                ListingIdentity.require(owner);var owned=new ArrayList<SyncRunRepository.Run>();
                for(var run:reports.list(owner.getId(),0,100))if(Set.of(RegistrationSources.KIND,RegistrationAttemptReports.KIND,"catalog_repair","znack_catalog_read","znack_marketplace").contains(run.kind())) {
                    try{reports.assertOwner(owner,run.id());owned.add(run);}catch(SecurityException oldOwner){/* historical account */}
                }
                var run=owned.stream().filter(r->r.id().equals(requested)).findFirst().orElse(owned.stream().filter(r->RegistrationAttemptReports.KIND.equals(r.kind())).findFirst().orElse(owned.isEmpty()?null:owned.getFirst()));
                if(run==null)return new Page(owned,null,List.of(),Map.of(),List.of(),0,Set.of(),"znack.registration.report_scope_batch",List.of());
                var items=reports.items(run.id(),start,300);RegistrationAttemptReports.refreshAll(owner,run);com.vncode.app.integration.znack.registration.CatalogErrorRepairService.refresh(owner,run,items);
                run=reports.find(run.id()).orElseThrow();var filtered=RegistrationReportData.page(owner,run.id(),filter,start,300);items=filtered.rows();
                for(int i=0;i<owned.size();i++)if(owned.get(i).id().equals(run.id()))owned.set(i,run);
                return new Page(owned,run,items,"znack_marketplace".equals(run.kind())?reports.marketplaceCounts(run.id()):reports.counts(run.id()),reports.events(run.id(),null,4),filtered.matched(),filtered.states(),RegistrationReportData.scopeKey(run,reports.items(run.id(),0,1)),filtered.failures());
            }};running=task;
            task.setOnSucceeded(e->{if(closed)return;if(pendingReload){pendingReload=false;busy=false;load();return;}var page=task.getValue();runs.getItems().setAll(page.runs());runs.setValue(page.run());selected=page.run()==null?"":page.run().id();table.getItems().setAll(page.items());matched=page.matched();
                String chosen=state.getValue();var states=new LinkedHashSet<String>();states.add("ALL");if(chosen!=null)states.add(chosen);page.states().stream().sorted().forEach(states::add);applyingFilters=true;try{state.getItems().setAll(states);state.setValue(chosen==null?"ALL":chosen);}finally{applyingFilters=false;}
                summary.setText(page.run()==null?tr("znack.registration.report_empty"):owner.getMarketplace()+" · "+owner.getName()+" · "+tr(page.scopeKey())+"\n"+runState(page.run())+" · "+label("znack.registration.report_step."+page.run().step(),page.run().step())+" · "+reasonCode(page.run().error())+"\n"+summary(page.run(),page.counts())+"\n"+tr("znack.registration.report_filtered")+": "+(page.items().isEmpty()?0:start+1)+"–"+(start+page.items().size())+" / "+matched+" · "+tr(page.scopeKey())+": "+page.run().total()+failureSummary(page.failures()));
                events.setText(page.events().stream().map(event->event.time()+" · "+label("znack.registration.report_step."+event.step(),event.step())+" · "+label("jobs.status."+event.state(),event.state())+(event.error().isBlank()?"":" · "+reasonCode(event.error()))).collect(java.util.stream.Collectors.joining("\n")));
                pendingReload=false;busy=false;buttons();timer.playFromStart();
            });
            java.util.function.Consumer<Throwable> failed=error->{if(closed)return;pendingReload=false;if(error instanceof SecurityException){table.getItems().clear();selected="";matched=0;runs.getItems().clear();}busy=false;summary.setText(RegistrationDiagnostics.readWarning(SyncFailure.of(error,0).code(),!table.getItems().isEmpty()));buttons();};
            task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
        }
        private void buttons(){refresh.setDisable(busy);runs.setDisable(busy);previous.setDisable(busy||offset==0);next.setDisable(busy||offset+table.getItems().size()>=matched);export.setDisable(exporting||busy||selected==null||selected.isBlank());}
        private RegistrationReportData.Filter filter(){return new RegistrationReportData.Filter(search.getText(),state.getValue());}
        private void export(){
            var run=runs.getValue();if(run==null||exporting)return;var chooser=new javafx.stage.FileChooser();chooser.setTitle(tr("jobs.export"));chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV","*.csv"));chooser.setInitialFileName("VNCode-"+run.id()+".csv");var file=chooser.showSaveDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;var filter=filter();exporting=true;buttons();var task=new Task<Void>(){protected Void call()throws Exception{RegistrationReportData.exportCsv(owner,run,filter,file.toPath());return null;}};exportTask=task;
            task.setOnSucceeded(e->{if(!closed){exporting=false;events.setText(tr("znack.registration.report_exported"));buttons();}});java.util.function.Consumer<Throwable> failed=error->{if(!closed){exporting=false;events.setText(tr("jobs.export")+" · "+reasonCode(SyncFailure.of(error,0).code()));buttons();}};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
        }
        private ListCell<SyncRunRepository.Run> runCell(){return new ListCell<>(){protected void updateItem(SyncRunRepository.Run run,boolean empty){super.updateItem(run,empty);setText(empty||run==null?"":run.created()+" · "+label("znack.registration.report_kind."+run.kind(),run.kind())+" · "+runState(run));}};}
        private void column(String text,Function<SyncRunRepository.Item,String> value){var column=new TableColumn<SyncRunRepository.Item,String>(text);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));column.setMinWidth(110);column.setPrefWidth(170);RegistrationTextCells.wrap(column);table.getColumns().add(column);}
        void dispose(){closed=true;timer.stop();filterDebounce.stop();if(running!=null)running.cancel(true);if(exportTask!=null)exportTask.cancel(true);}
    }
    static String summary(SyncRunRepository.Run run,Map<String,Long> counts){
        if("znack_marketplace".equals(run.kind()))return java.text.MessageFormat.format(tr("gtin.background.report_summary"),counts.getOrDefault("UPDATED",0L),counts.getOrDefault("WITH_GTIN",0L),counts.getOrDefault("NOT_UPDATED",0L),counts.getOrDefault("WITHOUT_GTIN",0L),counts.getOrDefault("BLOCKED",0L))+"\n"+tr("znack.registration.report_submitted")+": "+counts.getOrDefault("SUBMITTED",0L)+" · "+tr("znack.registration.report_pending")+": "+counts.getOrDefault("PENDING",0L)+" · "+tr("znack.registration.report_failed")+": "+counts.getOrDefault("FAILED",0L)+" · "+tr("znack.registration.report_unverified")+": "+counts.getOrDefault("UNVERIFIED",0L);
        if(RegistrationAttemptReports.KIND.equals(run.kind()))return java.text.MessageFormat.format(tr("znack.registration.batch_summary"),run.total(),counts.getOrDefault("QUEUED",0L),counts.getOrDefault("RUNNING",0L),counts.getOrDefault("WAITING_REMOTE",0L),counts.getOrDefault("SUCCEEDED",0L),counts.getOrDefault("NEEDS_ACTION",0L)+counts.getOrDefault("FAILED",0L),counts.getOrDefault("CANCELLED",0L));
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->label("jobs.status."+e.getKey(),e.getKey())+": "+e.getValue()).collect(java.util.stream.Collectors.joining(" · "));
    }
    static String itemReason(SyncRunRepository.Item row){
        String code=row.error();
        String message=reasonCode(code);
        String detail=identifier(row,"reason");
        return message+(detail.isBlank()||detail.equals(code)?"":" · "+detail);
    }
    static String failureSummary(List<SyncRunRepository.Item> failures){
        return failures.stream().map(row->{
            String detail=itemReason(row);
            if(detail.length()>300)detail=detail.substring(0,300)+"…";
            return "\n"+tr("jobs.reason")+" · "+row.article()+" / "+row.size()+": "+detail;
        }).collect(java.util.stream.Collectors.joining());
    }
    static String nextAction(SyncRunRepository.Item row){return reasonCode(RegistrationReportData.field(row,"nextAction"));}
    private static String reasonCode(String code){
        if(code.isBlank())return "";String message=label("znack.registration.reason."+code,FriendlyErrorService.format(new IllegalArgumentException(code)));
        return message+(message.contains("["+code+"]")?"":" ["+code+"]");
    }
    private static String identifier(SyncRunRepository.Item row,String name){
        return RegistrationReportData.field(row,name);
    }
    private static String result(SyncRunRepository.Item row,String key){String code=RegistrationReportData.field(row,key);return code.isBlank()?("checkResult".equals(key)?tr("znack.registration.gtin_unverified"):"—"):label("jobs.status."+code,code);}
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
