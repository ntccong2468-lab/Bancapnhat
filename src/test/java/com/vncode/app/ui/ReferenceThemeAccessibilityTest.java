package com.vncode.app.ui;

import com.vncode.app.MainApplication;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests computed JavaFX paints rather than just the values written in a stylesheet. */
class ReferenceThemeAccessibilityTest {
    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    @Test void primaryHoverAndKeyboardFocusRemainVisibleAndLightMetadataReadable() throws Exception {
        var test = new FutureTask<Void>(() -> {
            var root = new VBox(12);
            root.getStyleClass().addAll("theme-dark", "reference-dark");
            var button = new Button("Áp dụng");
            button.getStyleClass().add("btn-primary");
            root.getChildren().add(button);
            var scene = new Scene(root, 450, 200);
            scene.getStylesheets().add(MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());
            scene.getStylesheets().add(MainApplication.class.getResource("/com/vncode/app/styles/reference-dark.css").toExternalForm());
            var stage = new Stage();
            stage.setScene(scene);
            try {
                stage.show();
                button.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
                button.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
                root.applyCss(); root.layout();
                Color fill = (Color) button.getBackground().getFills().getFirst().getFill();
                Color ink = (Color) button.getTextFill();
                Color focus = (Color) button.getBorder().getStrokes().getFirst().getTopStroke();
                assertTrue(contrast(ink, fill) >= 4.5, "Primary hover text must remain readable");
                assertTrue(contrast(focus, fill) >= 3.0, "Keyboard focus must remain visible");

                var light = new VBox();
                light.getStyleClass().add("theme-light");
                var metadata = new Label("Thông tin sản phẩm");
                metadata.getStyleClass().add("text-muted");
                light.getChildren().add(metadata);
                scene.setRoot(light); light.applyCss(); light.layout();
                Color lightBackground = (Color) light.getBackground().getFills().getFirst().getFill();
                assertTrue(contrast((Color) metadata.getTextFill(), lightBackground) >= 4.5,
                        "Metadata text must remain readable on the light canvas");
            } finally { stage.close(); }
            return null;
        });
        Platform.runLater(test);
        test.get(15, TimeUnit.SECONDS);
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05);
    }
    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }
    private static double linear(double c) { return c <= 0.04045 ? c/12.92 : Math.pow((c+0.055)/1.055,2.4); }
}
