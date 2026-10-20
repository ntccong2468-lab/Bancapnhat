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
            int failures;String last="";
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
                    if(!message.equals(last)){last=message;completed.accept(snapshot.result());if(host.getScene()!=null)refresh.run();}
                    if(snapshot.settled()){pill.getChildren().remove(spinner);RegistrationStatusPill.dismissAfter(host,pill,dismiss);}
                    else next();
                }));
            }
        }
        new Observer().read();
    }
    static String summary(ZnackGtinBackgroundService.Result result){
        var p=result.progress();var stock=result.inventory();
        return java.text.MessageFormat.format(I18nService.getInstance().tr("gtin.background.summary"),p.succeeded(),stock.withGtin(),Math.max(0,stock.withGtin()-p.succeeded()),stock.withoutGtin(),p.pending(),p.blocked()+stock.blockedBeforeUpdate());
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
