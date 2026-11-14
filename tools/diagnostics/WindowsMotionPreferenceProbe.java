package com.vncode.tools.diagnostics;

import com.vncode.app.config.Database;
import com.vncode.app.shared.ConfigService;
import com.vncode.app.shared.ThemeService;
import com.vncode.app.shared.WorkspaceInteractionMotion;
import com.vncode.app.shared.WorkspaceMotion;
import com.google.gson.GsonBuilder;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A standalone public-API diagnostic. It does not launch MainApplication or use seller/license APIs. */
public final class WindowsMotionPreferenceProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected evidence.json and expectedReduced (any/true/false)");
        Path evidence=Path.of(args[0]).toAbsolutePath(); Files.createDirectories(evidence.getParent());
        System.setProperty("vncode.appdata.dir",Files.createTempDirectory(evidence.getParent(),"synthetic-motion-profile-").toString());
        Database.initDatabase(); var result=new LinkedHashMap<String,Object>();
        result.put("atUtc", Instant.now().toString()); result.put("os",System.getProperty("os.name")); result.put("expectedReduced",args[1]);
        Platform.startup(()->Platform.setImplicitExit(false));
        var work=new FutureTask<Void>(()->{
            var preferences=Platform.getPreferences();
            result.put("windowsSpiClientAreaAnimation",preferences.getBoolean("Windows.SPI.ClientAreaAnimation").orElse(null));
            result.put("platformReducedMotion",preferences.isReducedMotion());
            ThemeService.initializeAppearance(); ThemeService.setReducedMotion(false);
            result.put("savedAppReducedMotion",ConfigService.getConfigValue("ui_reduced_motion"));
            result.put("effectiveReducedMotion",ThemeService.isReducedMotion());
            if(!args[1].equals("any") && preferences.isReducedMotion()!=Boolean.parseBoolean(args[1])) throw new AssertionError("Actual JavaFX OS preference differs from requested worker phase");
            if(ThemeService.isReducedMotion()!=preferences.isReducedMotion()) throw new AssertionError("Saved false must preserve OS accessibility authority");
            var calls=new AtomicInteger(); var button=new Button("Synthetic action"); button.setOnAction(event->calls.incrementAndGet());
            var owner=new Stage(); owner.setScene(new Scene(new StackPane(button),480,320)); ThemeService.applyTheme(owner.getScene());
            try {
                owner.show(); owner.getScene().getRoot().applyCss(); owner.getScene().getRoot().layout();
                press(button); boolean scale=button.getTransforms().stream().anyMatch(Scale.class::isInstance);
                result.put("pressHasSpatialScale",scale);
                if(scale==preferences.isReducedMotion()) throw new AssertionError("Press transform must match the real OS motion preference");
                button.fire(); if(calls.get()!=1)throw new AssertionError("Motion preference must not block/refire the real action");
                ThemeService.setReducedMotion(true);
                if(!ThemeService.isReducedMotion()||button.getTransforms().stream().anyMatch(Scale.class::isInstance))throw new AssertionError("Saved reduced motion must cancel current feedback");
                ThemeService.setReducedMotion(false);
                var report=new Stage(); report.initOwner(owner); report.setScene(new Scene(new StackPane(new Label("Synthetic report")),240,160)); ThemeService.applyTheme(report.getScene());
                try {
                    report.show(); report.hide(); long ghosts=((StackPane)owner.getScene().getRoot()).getChildren().stream()
                            .filter(node->!node.isManaged()&&node.isMouseTransparent()).count(); result.put("reportClosingCopies",ghosts);
                    if(preferences.isReducedMotion() && ghosts!=0)throw new AssertionError("OS reduced motion must suppress closing copies");
                } finally {report.close();}
                result.put("businessActionCalls",calls.get()); result.put("status","PASS");
            } finally { WorkspaceInteractionMotion.cancelAll(); WorkspaceMotion.cancelAll(); owner.close(); }
            return null;
        });
        Throwable problem=null;
        try { Platform.runLater(work); work.get(30,TimeUnit.SECONDS); }
        catch(Throwable failure){problem=failure;result.put("status","FAIL");result.put("failure",failure.toString());}
        finally {Files.writeString(evidence,new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(result));Platform.exit();}
        System.out.println(new GsonBuilder().serializeNulls().create().toJson(result));
        System.exit(problem==null?0:1);
    }

    private static void press(Button button) {
        Event.fireEvent(button,new MouseEvent(button,button,MouseEvent.MOUSE_PRESSED,8,8,8,8,MouseButton.PRIMARY,1,
                false,false,false,false,true,false,false,false,false,true,new PickResult(button,8,8)));
    }
}
