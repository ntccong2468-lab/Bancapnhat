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
        var frozen=Set.copyOf(selected);String key=ListingIdentity.fingerprint(owner);var prior=RUNNING.get(key);if(prior!=null){attach(host,prior,refresh);return;}
        var shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());
        String action=I18nService.getInstance().tr(shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON?"znack.registration.update_ozon":"znack.registration.update_wb");
        var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call()throws Exception{
            updateMessage(action+"…");return new ZnackGtinBackgroundService().run(shop,frozen,p->{updateProgress(p.completed(),Math.max(1,p.total()));updateMessage(action+"… "+p.completed()+"/"+p.total());});
        }};
        RUNNING.put(key,task);attach(host,task,refresh);
        task.setOnSucceeded(e->RUNNING.remove(key,task));
        java.util.function.Consumer<Throwable> failure=error->{RUNNING.remove(key,task);if(task.getState()==javafx.concurrent.Worker.State.READY)finish(host,FriendlyErrorService.format(error));};
        task.setOnFailed(e->failure.accept(task.getException()));AppTaskExecutor.executeCatalog(task,failure);
    }
    static void attach(StackPane host,Task<ZnackGtinBackgroundService.Result> task,Runnable refresh){
        remove(host);var text=new Label();text.textProperty().bind(task.messageProperty());text.setWrapText(true);text.setMinWidth(0);
        text.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(100,host.widthProperty().subtract(90)));
        var spinner=new ProgressIndicator();spinner.setMaxSize(20,20);var pill=new HBox(10,spinner,text);pill.setAlignment(Pos.CENTER);install(host,pill);
        task.stateProperty().addListener(new javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State>(){
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.concurrent.Worker.State> o,javafx.concurrent.Worker.State old,javafx.concurrent.Worker.State state){
                if(state==javafx.concurrent.Worker.State.SUCCEEDED){
                    task.stateProperty().removeListener(this);text.textProperty().unbind();var p=task.getValue().progress();
                    finish(host,I18nService.getInstance().tr("gtin.background.result")+": "+p.succeeded()+" · "+I18nService.getInstance().tr("gtin.background.pending")+": "+p.pending()+" · "+I18nService.getInstance().tr("gtin.background.blocked")+": "+p.blocked());if(host.getScene()!=null)refresh.run();
                }else if(state==javafx.concurrent.Worker.State.FAILED||state==javafx.concurrent.Worker.State.CANCELLED){task.stateProperty().removeListener(this);text.textProperty().unbind();finish(host,FriendlyErrorService.format(task.getException()==null?new java.util.concurrent.CancellationException():task.getException()));}
            }
        });
    }
    private static void finish(StackPane host,String message){remove(host);var text=new Label(message);text.setWrapText(true);text.setMaxWidth(680);install(host,new HBox(text));}
    private static void install(StackPane host,HBox pill){pill.setId("gtinBackgroundPill");pill.getStyleClass().add("task-status-pill");pill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new Insets(12));host.getChildren().add(pill);}
    private static void remove(StackPane host){host.getChildren().removeIf(n->"gtinBackgroundPill".equals(n.getId()));}
}
