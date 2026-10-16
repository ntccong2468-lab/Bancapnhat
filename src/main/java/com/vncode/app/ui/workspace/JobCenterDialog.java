package com.vncode.app.ui.workspace;

import com.vncode.app.features.jobs.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.shared.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.*;
import java.util.function.*;

/** Live report browser; each row retains its job identity and opens exact item progress. */
public final class JobCenterDialog {
    private JobCenterDialog() {}
    public static void show(Window owner,Consumer<JobCenterRepository.Row> open) {
        var dialog=new Dialog<Void>();dialog.setTitle(tr("jobs.title"));if(owner!=null)dialog.initOwner(owner);
        var table=new TableView<JobReportRepository.Report>();table.setId("jobReportsTable");table.setPrefHeight(370);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,tr("jobs.id"),r->r.id());column(table,tr("jobs.shop"),JobReportRepository.Report::shop);column(table,tr("jobs.kind"),r->tr("jobs.kind."+r.kind()));
        column(table,tr("jobs.step"),r->phase(r.step()));column(table,tr("znack.registration.status"),r->tr("jobs.status."+r.state()));column(table,tr("jobs.count"),r->Integer.toString(r.total()));column(table,tr("jobs.updated"),r->time(r.updated()));
        var hint=new Label(tr("jobs.loading"));hint.setWrapText(true);var query=new TextField();query.setPromptText(tr("jobs.search"));var states=new ComboBox<String>();states.setConverter(new javafx.util.StringConverter<>(){public String toString(String value){return value==null||value.isBlank()?tr("jobs.all_states"):tr("jobs.status."+value);}public String fromString(String ignored){return null;}});states.getItems().add("");states.getItems().addAll("RUNNING","AWAITING_CONFIRMATION","WAITING_REMOTE","RECONCILING","NEEDS_ACTION","SUCCEEDED","PARTIAL","FAILED","CANCELLED");states.setValue("");
        var rows=new ArrayList<JobReportRepository.Report>();Runnable filter=()->{String q=query.getText().strip().toLowerCase(Locale.ROOT),selected=states.getValue();String id=table.getSelectionModel().getSelectedItem()==null?"":table.getSelectionModel().getSelectedItem().id();TableRows.merge(table.getItems(),rows.stream().filter(r->(selected==null||selected.isBlank()||r.state().equals(selected))&&(r.id()+" "+r.shop()+" "+r.market()+" "+r.kind()+" "+tr("jobs.kind."+r.kind())+" "+phase(r.step())+" "+tr("jobs.status."+r.state())+" "+r.error()).toLowerCase(Locale.ROOT).contains(q)).toList(),JobReportRepository.Report::id);table.getItems().stream().filter(r->r.id().equals(id)).findFirst().ifPresent(r->table.getSelectionModel().select(r));};
        query.textProperty().addListener((o,a,b)->filter.run());states.valueProperty().addListener((o,a,b)->filter.run());
        var refresh=new Button(tr("znack.registration.refresh"));var details=new Button(tr("jobs.details"));details.setDisable(true);var go=new Button(tr("jobs.open"));go.setDisable(true);
        boolean[] loading={false},closed={false};
        Runnable reload=()->{if(loading[0]||closed[0])return;loading[0]=true;refresh.setDisable(true);AppTaskExecutor.execute(new javafx.concurrent.Task<List<JobReportRepository.Report>>() {
            protected List<JobReportRepository.Report> call(){return new JobReportRepository().list();}
            protected void succeeded(){loading[0]=false;if(closed[0])return;refresh.setDisable(false);rows.clear();rows.addAll(getValue());filter.run();hint.setText(tr("jobs.report_hint"));}
            protected void failed(){loading[0]=false;if(closed[0])return;refresh.setDisable(false);hint.setText(tr("jobs.read_failed"));}
        },error->{loading[0]=false;if(!closed[0]){refresh.setDisable(false);hint.setText(tr("jobs.read_failed"));}});};
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{details.setDisable(b==null);go.setDisable(b==null);});refresh.setOnAction(e->reload.run());
        details.setOnAction(e->{var row=table.getSelectionModel().getSelectedItem();if(row!=null)detail(dialog.getDialogPane().getScene().getWindow(),row);});
        table.setOnMouseClicked(e->{if(e.getClickCount()==2&&table.getSelectionModel().getSelectedItem()!=null)detail(dialog.getDialogPane().getScene().getWindow(),table.getSelectionModel().getSelectedItem());});
        go.setOnAction(e->{var r=table.getSelectionModel().getSelectedItem();if(r!=null){dialog.close();open.accept(new JobCenterRepository.Row(r.shopId(),r.shop(),r.kind(),r.state(),r.total()));}});
        var body=new VBox(10,new FlowPane(8,8,query,states),hint,table,new FlowPane(8,8,refresh,details,go));VBox.setVgrow(table,Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);dialog.getDialogPane().setPrefWidth(1050);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var timer=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(3),e->reload.run()));timer.setCycleCount(javafx.animation.Animation.INDEFINITE);dialog.setOnHidden(e->{closed[0]=true;timer.stop();});reload.run();timer.play();dialog.show();
    }
    private static void detail(Window owner,JobReportRepository.Report report){
        var dialog=new Dialog<Void>();dialog.initOwner(owner);dialog.setTitle(tr("jobs.report")+" · "+report.id());var info=new Label(report.shop()+" · "+report.market()+" · "+report.id());info.setWrapText(true);
        var items=new TableView<JobReportRepository.Line>();items.setId("jobReportItems");items.setPrefHeight(330);items.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(items,"Product",JobReportRepository.Line::product);column(items,"Variant",JobReportRepository.Line::variant);column(items,"SKU",JobReportRepository.Line::sku);column(items,tr("products.article"),JobReportRepository.Line::article);column(items,tr("fbo.column.size"),JobReportRepository.Line::size);column(items,tr("fbo.column.color"),JobReportRepository.Line::color);column(items,"GTIN",JobReportRepository.Line::gtin);column(items,tr("jobs.step"),r->phase(r.step()));column(items,tr("znack.registration.status"),r->tr("jobs.status."+r.state()));column(items,tr("jobs.reason"),JobReportRepository.Line::error);
        var events=new TextArea();events.setEditable(false);events.setWrapText(true);events.setPrefRowCount(5);var more=new Button(tr("products.load_more"));var refresh=new Button(tr("znack.registration.refresh"));var export=new Button(tr("jobs.export"));var reconcile=new Button(tr("jobs.reconcile"));var review=new Button(tr("jobs.review_mapping"));review.setDisable(true);
        items.getSelectionModel().selectedItemProperty().addListener((o,a,b)->review.setDisable(!report.detailed()||!report.kind().equals("znack_marketplace")||b==null||b.product().startsWith("catalog:")||!Set.of("AWAITING_CONFIRMATION","NEEDS_ACTION","FAILED").contains(b.state())));
        review.setOnAction(e->{var selected=items.getSelectionModel().getSelectedItem();if(selected!=null)com.vncode.app.ui.znackregistration.ZnackMappingReviewDialog.show(owner,report,selected);});
        boolean[] busy={false},closed={false};int[] offset={0};
        Consumer<Boolean> load=append->{if(busy[0]||closed[0])return;busy[0]=true;refresh.setDisable(true);int from=append?offset[0]:0;
            AppTaskExecutor.execute(new javafx.concurrent.Task<Detail>(){
                protected Detail call(){var repo=new JobReportRepository();return new Detail(repo.lines(report,from),report.detailed()?new SyncRunRepository().events(report.id(),null,100):List.of(),repo.refresh(report),report.detailed()?new SyncRunRepository().counts(report.id()):Map.of());}
                protected void succeeded(){busy[0]=false;if(closed[0])return;refresh.setDisable(false);var data=getValue();info.setText(data.report().shop()+" · "+data.report().market()+" · "+data.report().id()+"\n"+tr("jobs.status."+data.report().state())+" · "+phase(data.report().step())+" · "+data.counts().entrySet().stream().map(v->tr("jobs.status."+v.getKey())+": "+v.getValue()).collect(java.util.stream.Collectors.joining(" · "))+(data.report().error().isBlank()?"":"\n"+data.report().error()));if(!append)items.getItems().clear();items.getItems().addAll(data.lines());offset[0]=from+data.lines().size();more.setDisable(data.lines().size()<300);String history=data.events().stream().map(e->time(e.time())+" · "+phase(e.step())+" · "+tr("jobs.status."+e.state())+(e.error().isBlank()?"":" · "+e.error())).collect(java.util.stream.Collectors.joining("\n"));events.setText(history.isBlank()?tr("jobs.legacy_detail"):history);}
                protected void failed(){busy[0]=false;if(!closed[0]){refresh.setDisable(false);events.setText(tr("jobs.read_failed"));}}
            },error->{busy[0]=false;if(!closed[0]){refresh.setDisable(false);events.setText(tr("jobs.read_failed"));}});
        };
        more.setOnAction(e->load.accept(true));refresh.setOnAction(e->load.accept(false));
        // A report may request reconciliation, never blindly replay an unknown marketplace write.
        reconcile.setDisable(!report.detailed()||!report.kind().equals("znack_marketplace")||!Set.of("WAITING_REMOTE","RECONCILING","RUNNING").contains(report.state()));
        reconcile.setOnAction(e->{AppTaskExecutor.execute(new javafx.concurrent.Task<Boolean>(){
            protected Boolean call(){var shop=new com.vncode.app.features.shop.ShopRepository().findById(report.shopId());return shop!=null&&SyncRuntime.requestIfRunning(shop,SyncCoordinator.Stream.ZNACK);}
            protected void succeeded(){load.accept(false);}
            protected void failed(){events.setText(tr("jobs.read_failed"));}
        },error->events.setText(tr("jobs.read_failed")));});
        export.setOnAction(e->{var chooser=new javafx.stage.FileChooser();chooser.setInitialFileName("VNCode-task-"+report.id().replaceAll("[^A-Za-z0-9-]","_")+".json");chooser.getExtensionFilters().addAll(new javafx.stage.FileChooser.ExtensionFilter("JSON","*.json"),new javafx.stage.FileChooser.ExtensionFilter("CSV UTF-8","*.csv"));var file=chooser.showSaveDialog(owner);if(file==null)return;export.setDisable(true);
            AppTaskExecutor.execute(new javafx.concurrent.Task<Void>(){protected Void call()throws Exception{JobReportExporter.export(report,file.toPath());return null;}protected void succeeded(){export.setDisable(false);}protected void failed(){export.setDisable(false);events.setText(tr("jobs.export_failed"));}},error->{export.setDisable(false);events.setText(tr("jobs.export_failed"));});
        });
        var body=new VBox(10,info,items,events,new FlowPane(8,8,refresh,more,review,reconcile,export));VBox.setVgrow(items,Priority.ALWAYS);dialog.getDialogPane().setContent(body);dialog.getDialogPane().setPrefWidth(1150);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var timer=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(3),e->{if(offset[0]<=300)load.accept(false);}));timer.setCycleCount(javafx.animation.Animation.INDEFINITE);dialog.setOnHidden(e->{closed[0]=true;timer.stop();});load.accept(false);timer.play();dialog.show();
    }
    private record Detail(List<JobReportRepository.Line> lines,List<SyncRunRepository.Event> events,JobReportRepository.Report report,Map<String,Long> counts){}
    private static <T> void column(TableView<T> table,String title,Function<T,String> text){var c=new TableColumn<T,String>(title);c.setCellValueFactory(v->new ReadOnlyStringWrapper(text.apply(v.getValue())));table.getColumns().add(c);}
    private static String phase(String value){if(value==null||value.isBlank())return "";String key="jobs.phase."+value,translated=tr(key);return translated.equals(key)?value:translated;}
    private static String time(String value){if(value==null||value.isBlank())return "";try{return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(java.time.ZoneId.of("Europe/Moscow")).format(java.time.Instant.parse(value));}catch(java.time.format.DateTimeParseException old){return value;}}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
