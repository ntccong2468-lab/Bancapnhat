package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.sync.ZnackGtinBackgroundService;
import com.vncode.app.features.sync.GtinRunStatusSnapshot;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

/** Nonmodal, bottom-centre status. Closing a view never cancels an accepted write. */
final class GtinBackgroundStatus {
    private static final Map<String,Task<ZnackGtinBackgroundService.Result>> RUNNING=new HashMap<>();
    private static final Map<Task<ZnackGtinBackgroundService.Result>,GtinRunStatusSnapshot.Context> CONTEXTS=new java.util.concurrent.ConcurrentHashMap<>();
    private GtinBackgroundStatus(){}
    static boolean holdsRows(StackPane host){return host.getChildren().stream().filter(node->"gtinBackgroundPill".equals(node.getId())).filter(HBox.class::isInstance).map(HBox.class::cast).anyMatch(box->box.getChildren().stream().anyMatch(ProgressIndicator.class::isInstance));}
    static void start(StackPane host,Shop owner,Set<ProductKey> selected,Runnable refresh){
        start(host,owner,selected,refresh,result->{});
    }
    static void start(StackPane host,Shop owner,Set<ProductKey> selected,Runnable refresh,java.util.function.Consumer<ZnackGtinBackgroundService.Result> completed){
        var frozen=Set.copyOf(selected);String key=ListingIdentity.fingerprint(owner);var prior=RUNNING.get(key);if(prior!=null&&!terminal(prior.getState())){attach(host,prior,refresh,completed);return;}
        var shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());
        String action=I18nService.getInstance().tr(shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON?"znack.registration.update_ozon":"znack.registration.update_wb");
        var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call()throws Exception{
            CONTEXTS.put(this,GtinRunStatusSnapshot.capture(shop,frozen));
            updateMessage(action+"…");return new ZnackGtinBackgroundService().run(shop,frozen,p->{updateProgress(p.completed(),Math.max(1,p.total()));updateMessage(action+"… "+p.completed()+"/"+p.total()+" · "+I18nService.getInstance().tr("gtin.background.sent")+": "+p.submitted()+" · "+I18nService.getInstance().tr("gtin.background.result")+": "+p.succeeded());},
                    phase->updateMessage(I18nService.getInstance().tr("gtin.background.phase."+phase)));
        }};
        RUNNING.put(key,task);attach(host,task,refresh,completed);var originalPill=host.lookup("#gtinBackgroundPill");
        task.setOnSucceeded(e->{RUNNING.remove(key,task);CONTEXTS.remove(task);});
        java.util.function.Consumer<Throwable> failure=error->{RUNNING.remove(key,task);CONTEXTS.remove(task);finishRejected(host,originalPill,task,error);};
        task.setOnFailed(e->failure.accept(task.getException()));task.setOnCancelled(e->{RUNNING.remove(key,task);CONTEXTS.remove(task);});
        executeMutation(shop,task,failure);
    }
    static void finishRejected(StackPane host,javafx.scene.Node originalPill,Task<ZnackGtinBackgroundService.Result> task,Throwable error){
        if(task.getState()==javafx.concurrent.Worker.State.READY&&originalPill!=null&&host.getChildren().contains(originalPill))finishFailure(host,error);
    }
    /** Accepted GTIN writes must not inherit the three-minute deadline for catalog reads. */
    static java.util.concurrent.CompletableFuture<Void> executeMutation(Shop shop,Task<ZnackGtinBackgroundService.Result> task,java.util.function.Consumer<Throwable> failure){
        var result=SellerMutationExecutor.<Void>submit(shop,()->{
            try(var lane=MarketplaceRequestBudget.lane(true)){task.run();}return null;
        });
        result.whenComplete((ignored,error)->{if(error!=null)javafx.application.Platform.runLater(()->failure.accept(error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null?error.getCause():error));});
        return result;
    }
    static void attach(StackPane host,Task<ZnackGtinBackgroundService.Result> task,Runnable refresh){
        attach(host,task,refresh,result->{});
    }
    static void attach(StackPane host,Task<ZnackGtinBackgroundService.Result> task,Runnable refresh,java.util.function.Consumer<ZnackGtinBackgroundService.Result> completed){
        remove(host);var text=new Label();text.textProperty().bind(javafx.beans.binding.Bindings.when(task.messageProperty().isEmpty()).then(I18nService.getInstance().tr("gtin.background.phase.PREPARING")).otherwise(task.messageProperty()));text.setWrapText(true);text.setMinWidth(0);
        text.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(100,host.widthProperty().subtract(90)));
        var spinner=new ProgressIndicator();spinner.setMaxSize(20,20);var pill=new HBox(10,spinner,text);pill.setAlignment(Pos.CENTER);install(host,pill);
        var listener=new javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State>(){
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.concurrent.Worker.State> o,javafx.concurrent.Worker.State old,javafx.concurrent.Worker.State state){
                if(state==javafx.concurrent.Worker.State.SUCCEEDED){
                    task.stateProperty().removeListener(this);text.textProperty().unbind();var p=task.getValue().progress();
                    if(!host.getChildren().contains(pill))return;
                    var context=CONTEXTS.get(task);
                    if(context!=null)observe(host,context,task.getValue(),refresh,completed);
                    else {finish(host,summary(task.getValue()));completed.accept(task.getValue());if(host.getScene()!=null)refresh.run();}
                }else if(state==javafx.concurrent.Worker.State.FAILED||state==javafx.concurrent.Worker.State.CANCELLED){task.stateProperty().removeListener(this);text.textProperty().unbind();
                    if(!host.getChildren().contains(pill))return;
                    if(task.getException() instanceof ZnackGtinBackgroundService.RunException partial&&CONTEXTS.get(task)!=null){observe(host,CONTEXTS.get(task),partial.result(),refresh,completed,javafx.util.Duration.seconds(1),javafx.util.Duration.seconds(8),partial);return;}
                    if(task.getException() instanceof ZnackGtinBackgroundService.RunException partial)completed.accept(partial.result());
                    finishFailure(host,task.getException()==null?new java.util.concurrent.CancellationException("GTIN_SYNC_CANCELLED"):task.getException());}
            }
        };
        task.stateProperty().addListener(listener);
        if(terminal(task.getState()))listener.changed(task.stateProperty(),task.getState(),task.getState());
    }
    /** Poll only the durable local report. The worker performs all API readback independently. */
    static void observe(StackPane host,GtinRunStatusSnapshot.Context context,ZnackGtinBackgroundService.Result initial,Runnable refresh,java.util.function.Consumer<ZnackGtinBackgroundService.Result> completed){
        observe(host,context,initial,refresh,completed,javafx.util.Duration.seconds(1),javafx.util.Duration.seconds(8));
    }
    static void observe(StackPane host,GtinRunStatusSnapshot.Context context,ZnackGtinBackgroundService.Result initial,Runnable refresh,java.util.function.Consumer<ZnackGtinBackgroundService.Result> completed,javafx.util.Duration interval,javafx.util.Duration dismiss){
        observe(host,context,initial,refresh,completed,interval,dismiss,null);
    }
    private static void observe(StackPane host,GtinRunStatusSnapshot.Context context,ZnackGtinBackgroundService.Result initial,Runnable refresh,java.util.function.Consumer<ZnackGtinBackgroundService.Result> completed,javafx.util.Duration interval,javafx.util.Duration dismiss,Throwable failure){
        remove(host);var text=new Label(summary(initial));text.setWrapText(true);text.setMaxWidth(680);
        var spinner=new ProgressIndicator();spinner.setMaxSize(20,20);var pill=new HBox(10,spinner,text);pill.setAlignment(Pos.CENTER);install(host,pill);
        if(failure!=null){var details=new Button(I18nService.getInstance().tr("znack.signature.error.details"));details.setId("gtinBackgroundErrorDetails");details.setOnAction(e->AlertService.showDetailedError(FriendlyErrorService.format(failure),com.vncode.app.integration.znack.ZnackErrorDetails.format(failure)));pill.getChildren().add(details);}
        var scene=host.getScene();
        final class Observer {
            int failures;String last="";java.util.function.Consumer<GtinRunStatusSnapshot.Snapshot> report;
            boolean current(){
                if(!host.getChildren().contains(pill))return false;
                if(host.getScene()!=scene){host.getChildren().remove(pill);return false;}
                return true;
            }
            void next(){if(!current())return;var pause=new javafx.animation.PauseTransition(interval);pause.setOnFinished(e->read());pause.play();}
            void read(){
                if(!current())return;
                var key=new ShopReadCoordinator.Key(context.shop().getId(),"GTIN_REPORT",initial.run()+":"+context.revision()+":"+context.directory().hashCode()+":"+context.selected().hashCode());
                ShopReadCoordinator.shared().submitLocal(key,()->GtinRunStatusSnapshot.read(context,initial.run())).whenComplete((snapshot,error)->javafx.application.Platform.runLater(()->{
                    if(!current())return;
                    if(error!=null){var cause=error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null?error.getCause():error;
                        if(cause instanceof SecurityException){host.getChildren().remove(pill);return;}
                        if(++failures<3){next();return;}finishFailure(host,cause);return;
                    }
                    failures=0;String message=summary(snapshot.result())+(failure==null?"":"\n"+FriendlyErrorService.format(failure));text.setText(message);
                    if(!message.equals(last)){last=message;completed.accept(snapshot.result());}
                    if(report==null)report=showResult(host,context,snapshot);else report.accept(snapshot);
                    if(snapshot.settled()){pill.getChildren().remove(spinner);if(host.getScene()!=null)refresh.run();RegistrationStatusPill.dismissAfter(host,pill,dismiss);}
                    else next();
                }));
            }
        }
        new Observer().read();
    }
    static String resultText(GtinRunStatusSnapshot.Snapshot snapshot){
        var result=snapshot.result();var stock=result.inventory();int updated=result.progress().succeeded();
        var i18n=I18nService.getInstance();String counts=java.text.MessageFormat.format(i18n.tr("gtin.background.final_counts"),updated,Math.max(0,stock.withGtin()-updated),stock.withoutGtin(),stock.unknown());
        String reasons=snapshot.reasons().entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(12).map(e->e.getValue()+" · "+RegistrationDiagnostics.reason(e.getKey())).collect(java.util.stream.Collectors.joining("\n"));
        return counts+(reasons.isBlank()?"":"\n\n"+reasons);
    }
    private static java.util.function.Consumer<GtinRunStatusSnapshot.Snapshot> showResult(StackPane host,GtinRunStatusSnapshot.Context context,GtinRunStatusSnapshot.Snapshot initial){
        if(host.getScene()==null||host.getScene().getWindow()==null||!host.getScene().getWindow().isShowing())return ignored->{};
        var dialog=new Dialog<Void>();dialog.initOwner(host.getScene().getWindow());AlertService.applyTheme(dialog);dialog.setTitle(I18nService.getInstance().tr("gtin.background.final_title"));dialog.getDialogPane().setId("gtinUpdateResultDialog");
        var text=new TextArea(resultText(initial));text.setEditable(false);text.setWrapText(true);text.setPrefRowCount(Math.min(12,4+initial.reasons().size()));text.setId("gtinUpdateResultText");dialog.getDialogPane().setContent(text);dialog.getDialogPane().setPrefWidth(620);
        var details=new ButtonType(I18nService.getInstance().tr("znack.registration.report"),ButtonBar.ButtonData.HELP);dialog.getDialogPane().getButtonTypes().setAll(details,ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(details).addEventFilter(javafx.event.ActionEvent.ACTION,event->{event.consume();RegistrationSyncReportDialog.show(host.getScene().getWindow(),context.shop(),initial.result().run());});DialogLayout.install(dialog.getDialogPane());dialog.show();
        return snapshot->{if(dialog.isShowing())text.setText(resultText(snapshot));};
    }
    static String summary(ZnackGtinBackgroundService.Result result){
        var p=result.progress();var stock=result.inventory();
        String summary=java.text.MessageFormat.format(I18nService.getInstance().tr("gtin.background.summary"),p.succeeded(),stock.withGtin(),Math.max(0,stock.withGtin()-p.succeeded()),stock.withoutGtin(),p.pending(),p.blocked()+stock.blockedBeforeUpdate());
        return stock.unknown()>0?summary+" · "+I18nService.getInstance().tr("znack.registration.report_unverified")+": "+stock.unknown():summary;
    }
    private static boolean terminal(javafx.concurrent.Worker.State state){return state==javafx.concurrent.Worker.State.SUCCEEDED||state==javafx.concurrent.Worker.State.FAILED||state==javafx.concurrent.Worker.State.CANCELLED;}
    private static void finish(StackPane host,String message){remove(host);var text=new Label(message);text.setWrapText(true);text.setMaxWidth(680);var pill=new HBox(text);install(host,pill);RegistrationStatusPill.dismissAfter(host,pill,javafx.util.Duration.seconds(8));}
    private static void finishFailure(StackPane host,Throwable error){
        remove(host);String message=FriendlyErrorService.format(error)+(error instanceof ZnackGtinBackgroundService.RunException partial?"\n"+summary(partial.result()):"");var text=new Label(message);text.setWrapText(true);text.setMinWidth(0);
        text.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(100,host.widthProperty().subtract(210)));
        var details=new Button(I18nService.getInstance().tr("znack.signature.error.details"));details.setId("gtinBackgroundErrorDetails");
        details.setMinWidth(Region.USE_PREF_SIZE);
        details.setOnAction(e->AlertService.showDetailedError(message,com.vncode.app.integration.znack.ZnackErrorDetails.format(error)));
        var pill=new HBox(10,text,details);pill.setAlignment(Pos.CENTER);install(host,pill);RegistrationStatusPill.dismissAfter(host,pill,javafx.util.Duration.seconds(15));
    }
    private static void install(StackPane host,HBox pill){pill.setId("gtinBackgroundPill");pill.getStyleClass().add("task-status-pill");pill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new Insets(12));host.getChildren().add(pill);}
    private static void remove(StackPane host){host.getChildren().removeIf(n->"gtinBackgroundPill".equals(n.getId()));}
}
