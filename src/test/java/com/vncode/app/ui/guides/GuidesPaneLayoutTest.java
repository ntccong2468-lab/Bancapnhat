package com.vncode.app.ui.guides;

import com.vncode.app.features.guides.GuideMediaRepository;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GuidesPaneLayoutTest {
    @TempDir Path profile;

    @BeforeAll
    static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    @Test
    void mediaActionStaysAboveThePreviewAndExplainsReplacement() throws Exception {
        System.setProperty("vncode.appdata.dir", profile.toString());
        try {
            var photo = new java.awt.image.BufferedImage(64, 36, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var file = profile.resolve("guide-fixture.png");
            assertTrue(javax.imageio.ImageIO.write(photo, "png", file.toFile()));
            new GuideMediaRepository().save(new GuideMediaRepository.Media(
                    "license", GuideMediaRepository.Kind.IMAGE, file.toUri()));

            var check = new FutureTask<Void>(() -> {
                var view = new GuidesPane();
                var scene = new Scene(view, 740, 600);
                ThemeService.applyTheme(scene);
                var stage = new Stage();
                try {
                    stage.setScene(scene);
                    stage.show();
                    view.applyCss();
                    view.layout();
                    var section = (Parent) view.lookup("#guide_license");
                    var choose = (Button) section.lookup("#guideMediaAction");
                    var preview = section.lookup("#guideMediaFrame");
                    assertEquals(I18nService.getInstance().tr("guides.change_media"), choose.getText());
                    assertTrue(choose.localToScene(choose.getLayoutBounds()).getMaxY()
                            <= preview.localToScene(preview.getLayoutBounds()).getMinY());
                    var emptySection = (Parent) view.lookup("#guide_wb");
                    assertEquals(I18nService.getInstance().tr("guides.add_media"),
                            ((Button) emptySection.lookup("#guideMediaAction")).getText());
                } finally {
                    view.dispose();
                    stage.close();
                }
                return null;
            });
            Platform.runLater(check);
            check.get(15, TimeUnit.SECONDS);
        } finally {
            System.clearProperty("vncode.appdata.dir");
        }
    }
}
