package com.vncode.app.integration.update;

import com.vncode.app.config.Database;
import com.vncode.app.shared.*;
import com.vncode.app.ui.workspace.WorkspaceHeaderController;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UpdatePresentationTest {
    @TempDir Path root;
    private AppLanguage language;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    @BeforeEach void start(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();language=I18nService.getInstance().getCurrentLanguage();}
    @AfterEach void cleanup()throws Exception{fx(()->{I18nService.getInstance().setLanguage(language);return null;});System.clearProperty("vncode.appdata.dir");}

    @Test void releaseNotesAndDownloadChoicesAreVisibleAndMandatoryCannotBeSkipped()throws Exception {
        fx(()->{
            var info=new UpdateInfo();info.setVersion("1.4.4");info.setChangelog("Sửa kích hoạt license\nTăng tốc Ozon");info.setDownloadUrls(Map.of("release","https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.4"));
            var dialog=new UpdateDialogService().createDialog(info);
            var content=(VBox)dialog.getDialogPane().getContent();
            var notes=content.getChildren().stream().filter(TextArea.class::isInstance).map(TextArea.class::cast).findFirst().orElseThrow();
            assertEquals(info.getChangelog(),notes.getText());assertFalse(notes.isEditable());
            var buttons=dialog.getDialogPane().getButtonTypes();assertEquals(4,buttons.size());
            assertEquals(UpdateDialogService.UpdateChoice.DOWNLOAD,dialog.getResultConverter().call(buttons.get(0)));
            assertEquals(UpdateDialogService.UpdateChoice.DOWNLOAD_ONLY,dialog.getResultConverter().call(buttons.get(1)));
            assertEquals(UpdateDialogService.UpdateChoice.SKIP,dialog.getResultConverter().call(buttons.get(2)));
            assertEquals(UpdateDialogService.UpdateChoice.LATER,dialog.getResultConverter().call(buttons.get(3)));
            info.setMandatory(true);var mandatory=new UpdateDialogService().createDialog(info);
            assertEquals(3,mandatory.getDialogPane().getButtonTypes().size());
            for(var button:mandatory.getDialogPane().getButtonTypes())assertNotEquals(UpdateDialogService.UpdateChoice.SKIP,mandatory.getResultConverter().call(button));
            return null;
        });
    }

    @Test void headerKeepsKnownReleaseAfterLanguageChangeAndOpensItOnClick()throws Exception {
        fx(()->{
            var loader=FxmlViewLoader.loader(WorkspaceHeaderController.class,"workspace-header-view.fxml");
            Parent header=loader.load();new Scene(header);header.applyCss();header.layout();
            WorkspaceHeaderController controller=loader.getController();var button=(Button)header.lookup("#availableUpdateButton");
            assertFalse(button.isVisible());assertFalse(button.isManaged());
            var clicks=new AtomicInteger();controller.setOnUpdate(clicks::incrementAndGet);controller.setAvailableUpdate("1.4.4");
            try{assertTrue(button.isVisible());assertTrue(button.isManaged());button.fire();assertEquals(1,clicks.get());
                I18nService.getInstance().setLanguage(AppLanguage.VI);controller.applyTranslations();assertTrue(button.getText().contains("1.4.4"));
                controller.setAvailableUpdate(null);assertFalse(button.isVisible());assertFalse(button.isManaged());
            }finally{controller.dispose();}
            return null;
        });
    }
    private static <T>T fx(Callable<T> action)throws Exception{var task=new FutureTask<>(action);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
}
