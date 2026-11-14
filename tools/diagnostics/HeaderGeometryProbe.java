package com.vncode.app.ui.workspace;

import com.vncode.app.shared.*;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only production-FXML geometry probe over a disposable synthetic profile. */
public class HeaderGeometryProbe {
    static <T>T fx(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
    static void pulse()throws Exception{var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames>=6){stop();done.complete(null);}}}.start();return null;});done.get(10,TimeUnit.SECONDS);}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out);NativeGlassEvidenceTest.toolkit();var fixture=new NativeGlassEvidenceTest();fixture.profile=Files.createTempDirectory(out,"synthetic-profile-");fixture.seed();
        var loader=FxmlViewLoader.loader(WorkspaceHeaderController.class,"workspace-header-view.fxml");
        Stage stage=fx(()->{
            WorkspaceMotion.setReduced(true);I18nService.getInstance().setLanguage(AppLanguage.VI);
            Parent header=loader.load();var wrap=new StackPane(header);wrap.setId("headerContainer");
            var center=new BorderPane();center.setTop(wrap);var side=new Region();side.setPrefWidth(202);
            var root=new BorderPane(center,null,null,null,side);root.setPadding(new Insets(8));BorderPane.setMargin(side,new Insets(0,10,0,0));root.getStyleClass().add("workspace-shell");
            var result=new Stage();result.setScene(new Scene(root,1440,900));ThemeService.applyTheme(result.getScene());result.show();
            WorkspaceHeaderController controller=loader.getController();controller.setShops(List.of(new Shop(1,"Minh họa tổng hợp WB",Marketplace.WILDBERRIES,"","fixture")),null);controller.setControls(true,false,true,true);return result;
        });
        var rows=new ArrayList<Map<String,Object>>();
        for(int width:new int[]{2560,1920,1440,1366,1280,1440})for(String theme:List.of("light","dark"))for(String focus:List.of("selector","manage")){
            fx(()->{ThemeService.switchTheme(theme);double decoration=stage.getWidth()-stage.getScene().getWidth();stage.setWidth(width+decoration);var node=(Node)loader.getNamespace().get(focus.equals("selector")?"shopComboBox":"manageShopButton");node.requestFocus();return null;});pulse();
            rows.add(fx(()->{
                var combo=(ComboBox<?>)loader.getNamespace().get("shopComboBox");var manage=(MenuButton)loader.getNamespace().get("manageShopButton");
                var visualCombo=combo.localToScene(combo.getBoundsInLocal());var visualManage=manage.localToScene(manage.getBoundsInLocal());
                var layoutCombo=combo.localToScene(combo.getLayoutBounds());var layoutManage=manage.localToScene(manage.getLayoutBounds());
                var row=new LinkedHashMap<String,Object>();row.put("width",stage.getScene().getWidth());row.put("theme",theme);row.put("focus",focus);row.put("outputScale",List.of(stage.getOutputScaleX(),stage.getOutputScaleY()));row.put("rootScale",List.of(stage.getScene().getRoot().getScaleX(),stage.getScene().getRoot().getScaleY()));row.put("layoutGap",layoutManage.getMinX()-layoutCombo.getMaxX());row.put("visualGap",visualManage.getMinX()-visualCombo.getMaxX());row.put("comboVisual",visualCombo.toString());row.put("manageVisual",visualManage.toString());row.put("comboLayout",layoutCombo.toString());row.put("manageLayout",layoutManage.toString());row.put("comboEffect",Objects.toString(combo.getEffect()));row.put("manageEffect",Objects.toString(manage.getEffect()));return row;
            }));
        }
        Files.writeString(out.resolve("geometry.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(rows));
        fx(()->{((WorkspaceHeaderController)loader.getController()).dispose();stage.close();return null;});fixture.cleanup();Platform.exit();System.exit(0);
    }
}
