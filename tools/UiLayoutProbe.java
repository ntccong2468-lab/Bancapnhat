import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.*;
import com.vncode.app.shared.*;
import com.vncode.app.ui.workspace.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.stage.Stage;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ScrollPane;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Read-only UI capture with an explicitly synthetic dataset and test-only license authority. */
public final class UiLayoutProbe {
    private static <T>T fx(Callable<T> action)throws Exception {
        var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(30,TimeUnit.SECONDS);
    }
    private static Object field(Object object,String name)throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static boolean scrollChild(Node node) {
        for(Node parent=node.getParent();parent!=null;parent=parent.getParent())if(parent instanceof ScrollPane)return true;
        return false;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("UI fixture directory and output directory required");
        Path data=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]).toAbsolutePath();
        if(!Files.isRegularFile(data.resolve("fixture.json")))throw new IllegalArgumentException("Synthetic fixture marker required");
        Files.createDirectories(out);System.setProperty("vncode.appdata.dir",data.toString());Database.initDatabase();
        // Rehearsal controls cannot contact production seller services without a selected shop.
        try(var c=Database.getConnection();var q=c.createStatement()) {
            q.execute("INSERT OR REPLACE INTO app_config(key,value) VALUES('update_api_url','http://127.0.0.1:1')");
        }
        var started=new CountDownLatch(1);Platform.startup(started::countDown);
        if(!started.await(10,TimeUnit.SECONDS))throw new IllegalStateException("FX did not start");
        Platform.setImplicitExit(false);
        var authority=LicenseDomainFixture.active(out.resolve("synthetic-license"));LicenseRuntime.install(authority);
        var loader=FxmlViewLoader.loader(HomeController.class,"home-view.fxml");
        Parent root=fx(()->{
            Parent loaded=FxmlViewLoader.load(loader);
            // Geometry probes install a synthetic license. Stop the real background runtime
            // before queued shop-loading callbacks can populate it; network behavior is covered
            // separately by the deterministic coordinator/transport tests.
            ((AutoCloseable)field(loader.getController(),"syncRuntime")).close();
            return loaded;
        });HomeController home=loader.getController();
        WorkspaceNavigator navigator=(WorkspaceNavigator)field(home,"workspaceNavigator");
        // Keep fixture marketplace identities unselected: this probe is geometry, not live integration.
        fx(()->{((javafx.scene.control.ComboBox<?>)field(field(home,"workspaceHeaderController"),"shopComboBox")).getSelectionModel().clearSelection();return null;});
        Stage stage=fx(()->{
            var window=new Stage();var scene=new Scene(root,1024,600);
            scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());
            window.setScene(scene);window.show();return window;
        });
        var results=new ArrayList<Map<String,Object>>();
        var routes=List.of("welcome","products","packing","fbo-packing","fbo-orders","ozon","kiz-mapping","znack","znack-registration","gs1","print-history","designer","guides","finance","gtin-sync","listing-copy","license","tnved");
        try {
            for(AppLanguage language:AppLanguage.values())for(String theme:List.of("theme-dark","theme-light"))for(int[] size:new int[][]{{1024,600},{1366,768}}) {
                fx(()->{I18nService.getInstance().setLanguage(language);stage.setWidth(size[0]);stage.setHeight(size[1]);root.getStyleClass().removeAll("theme-dark","theme-light");root.getStyleClass().add(theme);return null;});
                for(String route:routes) {
                    fx(()->{
                        if(route.equals("gtin-sync")) {
                            var open=HomeController.class.getDeclaredMethod("showGtinSync");open.setAccessible(true);open.invoke(home);
                        } else navigator.show(route);
                        if(route.equals("znack"))((com.vncode.app.ui.znack.ZnackAutomationController)field(home,"znackAutomationController")).openSettings();
                        root.applyCss();root.layout();return null;
                    });
                    fx(()->{root.applyCss();root.layout();return null;});
                    List<String> overflow=fx(()->{
                        var errors=new ArrayList<String>();double width=stage.getScene().getWidth();
                        for(Node node:root.lookupAll(".button"))if(node instanceof ButtonBase button&&node.isVisible()&&node.isManaged()&&!scrollChild(node)) {
                            var bounds=node.localToScene(node.getBoundsInLocal());
                            if(bounds.getMinX() < -2 || bounds.getMaxX()>width+2)errors.add(Objects.toString(node.getId(),button.getText()));
                        }
                        return errors;
                    });
                    String file=route+"-"+language.code()+"-"+theme+"-"+size[0]+"x"+size[1]+".png";
                    if(language==AppLanguage.VI) {
                        var image=fx(()->stage.getScene().snapshot(null));int w=(int)image.getWidth(),h=(int)image.getHeight();
                        var png=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,w);
                        ImageIO.write(png,"png",out.resolve(file).toFile());
                    }
                    results.add(Map.of("route",route,"language",language.code(),"theme",theme,"width",size[0],"height",size[1],"horizontalButtonOverflow",overflow));
                }
            }
            var report=Map.of("version",com.vncode.app.BuildConfig.getAppVersion(),"liveSellerMutations",false,"shopContext","synthetic fixture; background sync disabled for geometry; no mutation actions invoked","backgroundSynchronizationDisabledForGeometry",true,"layouts",results);
            Files.writeString(out.resolve("ui-layout.json"),new Gson().toJson(report));
            if(results.stream().anyMatch(row->!((List<?>)row.get("horizontalButtonOverflow")).isEmpty()))throw new IllegalStateException("UI buttons overflow; inspect ui-layout.json");
        } finally {
            fx(()->{home.dispose();stage.close();return null;});authority.close();Platform.exit();
        }
    }
}
