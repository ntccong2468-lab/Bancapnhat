package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.RegistrationConnectionDiagnostics;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, nonmodal connection check; results never include credentials or signed payloads. */
final class RegistrationConnectionDialog {
    static void show(Window owner,Shop selected){
        if(selected==null)return;
        Shop frozen=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        var dialog=new Dialog<Void>();if(owner!=null)dialog.initOwner(owner);dialog.initModality(Modality.NONE);
        dialog.setTitle(tr("znack.connection.title"));
        var labels=new EnumMap<RegistrationConnectionDiagnostics.Step,Label>(RegistrationConnectionDiagnostics.Step.class);
        var body=new VBox(10);body.setPadding(new Insets(16));
        var explanation=new Label(tr("znack.connection.scope"));explanation.setWrapText(true);body.getChildren().add(explanation);
        for(var step:RegistrationConnectionDiagnostics.Step.values()){
            var label=new Label(text(new RegistrationConnectionDiagnostics.Event(step,RegistrationConnectionDiagnostics.State.WAITING,"")));
            label.setWrapText(true);label.setId("registrationConnection"+step.name());labels.put(step,label);body.getChildren().add(label);
        }
        dialog.getDialogPane().setContent(body);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var closed=new AtomicBoolean();var failureShown=new AtomicBoolean();
        var task=new Task<Void>(){@Override protected Void call()throws Exception{
            RegistrationConnectionDiagnostics.check(frozen,event->Platform.runLater(()->{if(!closed.get())labels.get(event.step()).setText(text(event));}));return null;
        }};
        Runnable failed=()->{if(!closed.get()&&failureShown.compareAndSet(false,true)){var last=new Label(tr("znack.connection.unfinished"));last.setWrapText(true);body.getChildren().add(last);}};
        task.setOnFailed(event->failed.run());task.setOnCancelled(event->failed.run());
        dialog.setOnHidden(event->{closed.set(true);task.cancel(true);});
        dialog.show();AppTaskExecutor.executeCatalog(task,error->Platform.runLater(failed));
    }
    private static String text(RegistrationConnectionDiagnostics.Event event){
        String step=tr("znack.connection.step."+event.step().name().toLowerCase(Locale.ROOT));
        String state=tr("znack.connection.state."+event.state().name().toLowerCase(Locale.ROOT));
        if(event.code().isBlank())return step+": "+state;
        String key="znack.connection.reason."+event.code(),reason=tr(key);
        return step+": "+state+(reason.equals(key)?"":" · "+reason)+" ["+event.code()+"]";
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    private RegistrationConnectionDialog(){}
}
