package com.vncode.app.ui.packing;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbReadbackPendingException;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WbReadbackPendingUiTest {
    @TempDir Path root;
    static <T> T fx(Callable<T> action)throws Exception {var task=new FutureTask<>(action);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @Test void acknowledgedWriteWithFailedReadbackShowsPendingWarningInsteadOfGenericError()throws Exception {
        try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Fixture','token','WILDBERRIES')");}
        var shop=new Shop(1,"Fixture",Marketplace.WILDBERRIES,null,"token");
        var previous=I18nService.getInstance().getCurrentLanguage();
        var controller=fx(()->{
            I18nService.getInstance().setLanguage(AppLanguage.VI);
            var loader=FxmlViewLoader.loader(PackingController.class,"packing-view.fxml");FxmlViewLoader.load(loader);
            var instance=(PackingController)loader.getController();
            var field=PackingController.class.getDeclaredField("shop");field.setAccessible(true);field.set(instance,shop);
            Class<?> action=Class.forName("com.vncode.app.ui.packing.PackingController$WriteAction");
            Object failing=Proxy.newProxyInstance(action.getClassLoader(),new Class<?>[]{action},(p,m,a)->{throw new WbReadbackPendingException("WB-GI-1",new IOException("private detail"));});
            var method=PackingController.class.getDeclaredMethod("runWriteTask",Shop.class,action);method.setAccessible(true);method.invoke(instance,shop,failing);
            return instance;
        });
        try {
            String[] dialog=null;
            for(int attempt=0;attempt<120&&dialog==null;attempt++){
                dialog=fx(()->{
                    for(Window window:List.copyOf(Window.getWindows()))if(window instanceof Stage stage&&stage.isShowing()&&stage.getScene()!=null){
                        var node=stage.getScene().lookup(".dialog-pane");
                        if(node instanceof DialogPane pane){String[] result={stage.getTitle(),pane.getHeaderText(),pane.getContentText()};stage.hide();return result;}
                    }
                    return null;
                });
                if(dialog==null)Thread.sleep(50);
            }
            assertNotNull(dialog,"Pending warning must be shown");
            assertEquals("Đang chờ xác nhận WB",dialog[0]);assertTrue(dialog[1].contains("WB đã nhận"));
            assertTrue(dialog[2].contains("WB-GI-1"));assertFalse(dialog[2].contains("private detail"));
        } finally {fx(()->{controller.dispose();I18nService.getInstance().setLanguage(previous);return null;});System.clearProperty("vncode.appdata.dir");}
    }
}
