package com.vncode.app.ui;

import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/** The first window is independent of SQL, remote APIs, licensing and recovery. */
public final class StartupProgress {
    @FunctionalInterface public interface Preparation<T>{T run(Consumer<String> phase)throws Exception;}
    private StartupProgress(){}
    public static <T> Task<T> show(Stage stage,Preparation<T> preparation,Consumer<T> ready,Consumer<Throwable> failed){
        var language=Locale.getDefault().getLanguage();
        if(!java.util.Set.of("vi","en","ru","zh").contains(language))language="en";
        var messages=ResourceBundle.getBundle("com.vncode.app.i18n.messages",Locale.forLanguageTag(language));
        var title=new Label("VN code");title.setStyle("-fx-font-size:26;-fx-font-weight:bold;");
        var phase=new Label(messages.getString("startup.phase.RECOVERING_DATA"));phase.setId("startupPhase");phase.setWrapText(true);
        var spinner=new ProgressIndicator();spinner.setMaxSize(32,32);
        var close=new Button(messages.getString("startup.close"));close.setVisible(false);close.setManaged(false);close.setOnAction(e->stage.close());
        var root=new VBox(18,title,spinner,phase,close);root.setAlignment(Pos.CENTER);root.setStyle("-fx-padding:36;");
        root.getStyleClass().addAll("liquid-glass","theme-light");
        com.vncode.app.shared.UiFonts.install();
        var scene=new Scene(root,580,260);scene.getProperties().put(com.vncode.app.shared.ThemeService.STARTUP_SCENE,Boolean.TRUE);scene.getStylesheets().add(StartupProgress.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());scene.getStylesheets().add(StartupProgress.class.getResource("/com/vncode/app/styles/liquid-glass.css").toExternalForm());
        stage.setTitle("VN code · "+messages.getString("startup.opening"));stage.setScene(scene);
        var task=new Task<T>(){@Override protected T call()throws Exception{return preparation.run(step->updateMessage(messages.getString("startup.phase."+step)));}};
        task.messageProperty().addListener((o,a,b)->{if(b!=null&&!b.isBlank())phase.setText(b);});
        var closing=new java.util.concurrent.atomic.AtomicBoolean();var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        task.setOnSucceeded(e->{if(stage.isShowing()&&!closing.get())try{ready.accept(task.getValue());}catch(RuntimeException failure){showFailure(phase,spinner,close,messages);failed.accept(failure);}});
        task.setOnFailed(e->{showFailure(phase,spinner,close,messages);failed.accept(task.getException());});
        stage.setOnCloseRequest(e->{if(active.get()){e.consume();closing.set(true);phase.setText(messages.getString("startup.closing"));task.cancel(true);}});stage.show();
        var worker=new Thread(()->{try{task.run();}finally{active.set(false);if(closing.get())javafx.application.Platform.runLater(stage::close);}},"vncode-startup-data");worker.setDaemon(true);worker.start();return task;
    }
    private static void showFailure(Label phase,ProgressIndicator spinner,Button close,ResourceBundle messages){
        phase.setText(messages.getString("startup.failed"));spinner.setVisible(false);spinner.setManaged(false);close.setManaged(true);close.setVisible(true);
    }
}
