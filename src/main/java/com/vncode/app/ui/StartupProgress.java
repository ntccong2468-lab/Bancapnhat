package com.vncode.app.ui;

import javafx.concurrent.Task;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** The first window is independent of SQL, remote APIs, licensing and recovery. */
public final class StartupProgress {
    @FunctionalInterface public interface Preparation<T>{T run(Consumer<String> phase)throws Exception;}
    private StartupProgress(){}
    public static <T> Task<T> show(Stage stage,Preparation<T> preparation,Consumer<T> ready,Consumer<Throwable> failed){
        return show(stage,preparation,ready,failed,System::nanoTime);
    }
    static <T> Task<T> show(Stage stage,Preparation<T> preparation,Consumer<T> ready,Consumer<Throwable> failed,LongSupplier clock){
        var language=Locale.getDefault().getLanguage();
        if(!java.util.Set.of("vi","en","ru","zh").contains(language))language="en";
        var messages=ResourceBundle.getBundle("com.vncode.app.i18n.messages",Locale.forLanguageTag(language));
        var title=new Label("VN code");title.setStyle("-fx-font-size:26;-fx-font-weight:bold;");
        var phase=new Label(messages.getString("startup.phase.RECOVERING_DATA"));phase.setId("startupPhase");phase.setWrapText(true);phase.setPrefWidth(500);phase.setMaxWidth(500);
        var spinner=new ProgressIndicator();spinner.setMaxSize(32,32);
        var elapsed=new Label();elapsed.setId("startupElapsed");
        var close=new Button(messages.getString("startup.close"));close.setId("startupClose");close.setOnAction(e->{var request=new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST);stage.fireEvent(request);if(!request.isConsumed())stage.close();});
        var copy=new Button(messages.getString("startup.copy_diagnostics"));copy.setId("startupCopyDiagnostics");
        var actions=new HBox(10,copy,close);actions.setAlignment(Pos.CENTER);actions.setVisible(false);actions.setManaged(false);
        var root=new VBox(18,title,spinner,phase,elapsed,actions);root.setAlignment(Pos.CENTER);root.setStyle("-fx-padding:36;");
        root.getStyleClass().addAll("theme-dark","reference-dark","workspace-theme");
        com.vncode.app.shared.UiFonts.install();
        var scene=new Scene(root,580,300);scene.getProperties().put(com.vncode.app.shared.ThemeService.STARTUP_SCENE,Boolean.TRUE);scene.getStylesheets().add(StartupProgress.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());
        scene.getStylesheets().add(StartupProgress.class.getResource("/com/vncode/app/styles/workspace-controls.css").toExternalForm());
        stage.setTitle("VN code · "+messages.getString("startup.opening"));stage.setScene(scene);
        var lastPhase=new java.util.concurrent.atomic.AtomicReference<>("RECOVERING_DATA");
        var closing=new java.util.concurrent.atomic.AtomicBoolean();var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        var opening=new java.util.concurrent.atomic.AtomicBoolean();var terminalFailure=new java.util.concurrent.atomic.AtomicBoolean();
        var workerReference=new java.util.concurrent.atomic.AtomicReference<Thread>();
        long started=clock.getAsLong();
        var task=new Task<T>(){@Override protected T call()throws Exception{return preparation.run(step->{lastPhase.set(step);updateMessage(messages.getString("startup.phase."+step));});}};
        task.messageProperty().addListener((o,a,b)->{if(!opening.get()&&!closing.get()&&!terminalFailure.get()&&b!=null&&!b.isBlank())phase.setText(b);});
        Runnable refresh=()->{
            long seconds=elapsedSeconds(clock,started);
            elapsed.setText(new MessageFormat(messages.getString("startup.elapsed"),messages.getLocale()).format(new Object[]{seconds}));
            if(seconds>=15&&!actions.isVisible()&&!closing.get()&&!terminalFailure.get()){actions.setManaged(true);actions.setVisible(true);stage.sizeToScene();}
        };
        var heartbeat=new Timeline(new KeyFrame(Duration.seconds(1),e->refresh.run()));heartbeat.setCycleCount(Timeline.INDEFINITE);
        copy.setOnAction(e->copyDiagnostic(copy,messages,runningDiagnostic(lastPhase.get(),elapsedSeconds(clock,started),workerReference.get())));
        javafx.beans.value.ChangeListener<Boolean> hiddenListener=(o,wasShowing,isShowing)->{if(!isShowing){heartbeat.stop();closing.set(true);task.cancel(true);}};
        // A later pulse renders the phase before FXML/controller work occupies the FX thread.
        var openAfterRender=new AnimationTimer(){private boolean firstPulse=true;@Override public void handle(long now){
            if(!stage.isShowing()||closing.get()||stage.getScene()!=scene){stop();return;}
            if(firstPulse){firstPulse=false;return;}
            stop();heartbeat.stop();
            try{ready.accept(task.getValue());}catch(RuntimeException failure){terminalFailure.set(true);showFailure(stage,phase,spinner,actions,copy,messages,lastPhase.get(),failure);failed.accept(failure);}
            finally{if(stage.getScene()!=scene)stage.showingProperty().removeListener(hiddenListener);}
        }};
        task.setOnSucceeded(e->{if(stage.isShowing()&&!closing.get()){
            opening.set(true);lastPhase.set("OPENING_WORKSPACE");phase.setText(messages.getString("startup.phase.OPENING_WORKSPACE"));
            if(stage.isShowing()&&!closing.get())openAfterRender.start();
        }});
        task.setOnFailed(e->{heartbeat.stop();openAfterRender.stop();terminalFailure.set(true);Throwable failure=task.getException();if(activationAcknowledged(failure)){closing.set(true);stage.close();return;}if(closing.get()||!stage.isShowing())return;showFailure(stage,phase,spinner,actions,copy,messages,lastPhase.get(),failure);failed.accept(failure);});
        stage.setOnCloseRequest(e->{closing.set(true);heartbeat.stop();openAfterRender.stop();if(active.get()){e.consume();phase.setText(messages.getString("startup.closing"));task.cancel(true);}});
        stage.showingProperty().addListener(hiddenListener);
        refresh.run();stage.show();heartbeat.play();
        var worker=new Thread(()->{try{task.run();}finally{active.set(false);if(closing.get())javafx.application.Platform.runLater(stage::close);}},"vncode-startup-data");worker.setDaemon(true);workerReference.set(worker);worker.start();return task;
    }
    private static long elapsedSeconds(LongSupplier clock,long started){return Math.max(0,java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(clock.getAsLong()-started));}
    private static void copyDiagnostic(Button copy,ResourceBundle messages,String diagnostic){
        try{var content=new ClipboardContent();content.putString(diagnostic);boolean copied=Clipboard.getSystemClipboard().setContent(content);copy.setText(messages.getString(copied?"startup.diagnostics_copied":"startup.diagnostics_copy_failed"));}catch(RuntimeException unavailable){copy.setText(messages.getString("startup.diagnostics_copy_failed"));}
    }
    private static String runningDiagnostic(String phase,long elapsed,Thread worker){
        var report=new StringBuilder(diagnostic(null,phase,"STARTUP_IN_PROGRESS")).append("\nElapsed seconds: ").append(elapsed);
        if(worker!=null)try{report.append("\nData worker state: ").append(worker.getState());int frames=0;for(var frame:worker.getStackTrace())if(frame.getClassName().startsWith("com.vncode.app.")&&frames++<6)report.append("\n  at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());}catch(SecurityException unavailable){/* The phase and elapsed time remain available. */}
        return report.toString();
    }
    private static void showFailure(Stage stage,Label phase,ProgressIndicator spinner,HBox actions,Button copy,ResourceBundle messages,String lastPhase,Throwable failure){
        String code=failureCode(failure,lastPhase);
        phase.setText(messages.getString("startup.failed")+"\n\n"+messages.getString("startup.failure."+code.substring("STARTUP_".length()).toLowerCase(Locale.ROOT))+"\n["+code+"]");
        spinner.setVisible(false);spinner.setManaged(false);actions.setManaged(true);actions.setVisible(true);
        String diagnostic=diagnostic(failure,lastPhase,code);
        copy.setOnAction(event->copyDiagnostic(copy,messages,diagnostic));
        stage.sizeToScene();
    }
    private static String failureCode(Throwable failure,String phase){
        var causes=new java.util.ArrayList<Throwable>();var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&causes.size()<12&&seen.add(cause);cause=cause.getCause())causes.add(cause);
        if(causes.stream().anyMatch(com.vncode.app.shared.AppDataLock.AlreadyRunningException.class::isInstance))return "STARTUP_ALREADY_RUNNING";
        if(causes.stream().anyMatch(cause->cause instanceof java.nio.file.AccessDeniedException||cause instanceof SecurityException))return "STARTUP_ACCESS_DENIED";
        if(causes.stream().anyMatch(cause->cause instanceof java.sql.SQLException sql&&(sql.getErrorCode()==5||sql.getErrorCode()==6)))return "STARTUP_DATABASE_BUSY";
        if(causes.stream().anyMatch(java.sql.SQLException.class::isInstance))return "STARTUP_DATABASE";
        return "OPENING_WORKSPACE".equals(phase)?"STARTUP_WORKSPACE":"STARTUP_PREPARATION";
    }
    private static boolean activationAcknowledged(Throwable failure){
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&seen.size()<12&&seen.add(cause);cause=cause.getCause())if(cause instanceof com.vncode.app.shared.AppInstanceActivation.ActivatedException)return true;
        return false;
    }
    /** Arbitrary messages, paths, payloads and environment values never enter the copied report. */
    private static String diagnostic(Throwable failure,String phase,String code){
        String version=com.vncode.app.BuildConfig.getAppVersion();if(!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))version="unknown";
        String safePhase=java.util.Set.of("RECOVERING_DATA","CHECKING_BACKUP","CREATING_BACKUP","INITIALIZING_DATA","OPENING_WORKSPACE").contains(phase)?phase:"UNKNOWN";
        var report=new StringBuilder("VN code version: ").append(version).append("\nStartup phase: ").append(safePhase).append("\nError code: ").append(code);
        if(failure!=null)report.append("\nERROR CHAIN");
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&seen.size()<12&&seen.add(cause);cause=cause.getCause()){
            report.append("\n").append(seen.size()).append(". ").append(cause.getClass().getName());
            if(cause instanceof java.sql.SQLException sql){String state=sql.getSQLState();report.append(" (SQL code ").append(sql.getErrorCode());if(state!=null&&state.matches("[A-Z0-9]{2,8}"))report.append(", state ").append(state);report.append(')');}
            int frames=0;for(var frame:cause.getStackTrace())if(frame.getClassName().startsWith("com.vncode.app.")&&frames++<3)report.append("\n  at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
        }
        return report.toString();
    }
}
