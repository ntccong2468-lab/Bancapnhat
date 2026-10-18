package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.sync.ZnackGtinBackgroundService;
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
    private GtinBackgroundStatus(){}
    static void start(StackPane host,Shop owner,Set<ProductKey> selected,Runnable refresh){
        var frozen=Set.copyOf(selected);String key=ListingIdentity.fingerprint(owner);var prior=RUNNING.get(key);if(prior!=null&&!terminal(prior.getState())){attach(host,prior,refresh);return;}
        var shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());
        String action=I18nService.getInstance().tr(shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON?"znack.registration.update_ozon":"znack.registration.update_wb");
        var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call()throws Exception{
            updateMessage(action+"…");return new ZnackGtinBackgroundService().run(shop,frozen,p->{updateProgress(p.completed(),Math.max(1,p.total()));updateMessage(action+"… "+p.completed()+"/"+p.total());},
                    phase->updateMessage(I18nService.getInstance().tr("gtin.background.phase."+phase)));
        }};
        RUNNING.put(key,task);attach(host,task,refresh);
        task.setOnSucceeded(e->RUNNING.remove(key,task));
        java.util.function.Consumer<Throwable> failure=error->{RUNNING.remove(key,task);if(task.getState()==javafx.concurrent.Worker.State.READY)finishFailure(host,error);};
        task.setOnFailed(e->failure.accept(task.getException()));task.setOnCancelled(e->RUNNING.remove(key,task));
        executeMutation(shop,task,failure);
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
        remove(host);var text=new Label();text.textProperty().bind(javafx.beans.binding.Bindings.when(task.messageProperty().isEmpty()).then(I18nService.getInstance().tr("gtin.background.phase.PREPARING")).otherwise(task.messageProperty()));text.setWrapText(true);text.setMinWidth(0);
        text.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(100,host.widthProperty().subtract(90)));
        var spinner=new ProgressIndicator();spinner.setMaxSize(20,20);var pill=new HBox(10,spinner,text);pill.setAlignment(Pos.CENTER);install(host,pill);
        var listener=new javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State>(){
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.concurrent.Worker.State> o,javafx.concurrent.Worker.State old,javafx.concurrent.Worker.State state){
                if(state==javafx.concurrent.Worker.State.SUCCEEDED){
                    task.stateProperty().removeListener(this);text.textProperty().unbind();var p=task.getValue().progress();
                    finish(host,I18nService.getInstance().tr("gtin.background.result")+": "+p.succeeded()+" · "+I18nService.getInstance().tr("gtin.background.pending")+": "+p.pending()+" · "+I18nService.getInstance().tr("gtin.background.blocked")+": "+p.blocked());if(host.getScene()!=null)refresh.run();
                }else if(state==javafx.concurrent.Worker.State.FAILED||state==javafx.concurrent.Worker.State.CANCELLED){task.stateProperty().removeListener(this);text.textProperty().unbind();finishFailure(host,task.getException()==null?new java.util.concurrent.CancellationException("GTIN_SYNC_CANCELLED"):task.getException());}
            }
        };
        task.stateProperty().addListener(listener);
        if(terminal(task.getState()))listener.changed(task.stateProperty(),task.getState(),task.getState());
    }
    private static boolean terminal(javafx.concurrent.Worker.State state){return state==javafx.concurrent.Worker.State.SUCCEEDED||state==javafx.concurrent.Worker.State.FAILED||state==javafx.concurrent.Worker.State.CANCELLED;}
    private static void finish(StackPane host,String message){remove(host);var text=new Label(message);text.setWrapText(true);text.setMaxWidth(680);install(host,new HBox(text));}
    private static void finishFailure(StackPane host,Throwable error){
        remove(host);String message=FriendlyErrorService.format(error);var text=new Label(message);text.setWrapText(true);text.setMinWidth(0);
        text.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(100,host.widthProperty().subtract(210)));
        var details=new Button(I18nService.getInstance().tr("znack.signature.error.details"));details.setId("gtinBackgroundErrorDetails");
        details.setMinWidth(Region.USE_PREF_SIZE);
        details.setOnAction(e->AlertService.showDetailedError(message,com.vncode.app.integration.znack.ZnackErrorDetails.format(error)));
        var pill=new HBox(10,text,details);pill.setAlignment(Pos.CENTER);install(host,pill);
    }
    private static void install(StackPane host,HBox pill){pill.setId("gtinBackgroundPill");pill.getStyleClass().add("task-status-pill");pill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new Insets(12));host.getChildren().add(pill);}
    private static void remove(StackPane host){host.getChildren().removeIf(n->"gtinBackgroundPill".equals(n.getId()));}
}
