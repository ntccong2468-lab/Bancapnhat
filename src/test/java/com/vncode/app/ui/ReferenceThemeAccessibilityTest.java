package com.vncode.app.ui;

import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Computed native paints, including dark-theme ink on the approved bright accent. */
class ReferenceThemeAccessibilityTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path profile;
    @org.junit.jupiter.api.BeforeEach void profile() {
        System.setProperty("vncode.appdata.dir", profile.toString());
        com.vncode.app.config.Database.initDatabase();
    }
    @org.junit.jupiter.api.AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }
    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    @Test void primaryHoverKeyboardFocusAndMetadataStayReadableInBothApprovedThemes() throws Exception {
        var test = new FutureTask<Void>(() -> {
            var stage=new Stage();
            try {
                for(var theme:ThemeService.Theme.values()) {
                    ThemeService.setCurrentTheme(theme);
                    var button=new Button("Áp dụng");button.getStyleClass().add("btn-primary");
                    var metadata=new Label("Thông tin sản phẩm");metadata.getStyleClass().add("text-muted");
                    var root=new VBox(12,button,metadata);var scene=new Scene(root,450,200);
                    stage.setScene(scene);ThemeService.applyTheme(scene);stage.show();root.applyCss();root.layout();
                    var normalBounds=button.getBoundsInParent();
                    button.requestFocus();
                    button.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"),true);
                    assertSame(button,scene.getFocusOwner(),"Native keyboard focus must reach the primary action");
                    button.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"),true);
                    root.applyCss();root.layout();
                    Color fill=paint(button),ink=(Color)button.getTextFill();
                    assertTrue(contrast(ink,fill)>=4.5,"Primary hover text contrast: "+theme);
                    assertFalse(button.getBorder().getStrokes().isEmpty(),"Keyboard focus needs a visible native border");
                    Color focus=(Color)button.getBorder().getStrokes().getFirst().getTopStroke();
                    assertTrue(focus.getOpacity()>.9,"Focus ring must stay opaque: "+theme);
                    assertTrue(contrast(focus,fill)>=3,"Keyboard focus contrast: "+theme);
                    assertEquals(normalBounds.getWidth(),button.getBoundsInParent().getWidth(),.01,"Hover/focus must not shift actions");
                    assertEquals(normalBounds.getHeight(),button.getBoundsInParent().getHeight(),.01,"Hover/focus must not shift actions");
                    assertTrue(contrast((Color)metadata.getTextFill(),paint(root))>=4.5,"Canvas metadata contrast: "+theme);
                    assertEquals(1,root.getOpacity());assertEquals(1,button.getOpacity());assertEquals(1,metadata.getOpacity());
                }
            } finally { stage.close(); }
            return null;
        });
        Platform.runLater(test);test.get(20,TimeUnit.SECONDS);
    }
    private static Color paint(Region region) {return (Color)region.getBackground().getFills().getFirst().getFill();}
    private static double contrast(Color first,Color second) {
        double a=luminance(first),b=luminance(second);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
    }
    private static double luminance(Color c) {
        return .2126*linear(c.getRed())+.7152*linear(c.getGreen())+.0722*linear(c.getBlue());
    }
    private static double linear(double c) {return c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4);}
}
