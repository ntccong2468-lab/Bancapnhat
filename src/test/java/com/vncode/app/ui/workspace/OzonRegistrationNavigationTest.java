package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.WorkspaceMotion;
import com.vncode.app.ui.znackregistration.OzonRegistrationPane;
import com.vncode.app.ui.znackregistration.ZnackCardRegistrationController;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** The new FBS action reuses the existing native Ozon registration route and owner. */
class OzonRegistrationNavigationTest {
    @TempDir Path profile;
    private String previousProfile;
    private HomeController home;
    private WorkspaceState state;
    private WorkspaceNavigator navigator;
    private ZnackCardRegistrationController registration;
    private Parent registrationView;
    private final AtomicInteger builds=new AtomicInteger();
    private final Shop first=new Shop(1,"Synthetic Ozon A",Marketplace.OZON,"11","fixture-a");
    private final Shop second=new Shop(2,"Synthetic Ozon B",Marketplace.OZON,"22","fixture-b");

    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    private static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @BeforeEach void initialize()throws Exception{
        previousProfile=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic Ozon A','OZON','11','fixture-a'),(2,'Synthetic Ozon B','OZON','22','fixture-b'),(3,'Synthetic WB','WILDBERRIES','','fixture-wb')");
        }
        fx(()->{
            home=new HomeController();home.dynamicContentContainer=new StackPane();
            state=(WorkspaceState)field(home,"state");navigator=(WorkspaceNavigator)field(home,"workspaceNavigator");
            navigator.register("znack-registration",()->{
                builds.incrementAndGet();var loader=FxmlViewLoader.loader(ZnackCardRegistrationController.class,"znack-card-registration-view.fxml");
                registrationView=FxmlViewLoader.load(loader);registration=loader.getController();registration.setOzonRegistrationFactory(OzonRegistrationPane::new);
                try{set(home,"znackRegistrationController",registration);}catch(Exception error){throw new IllegalStateException(error);}
                return registrationView;
            });
            state.setSelectedShop(first);return null;
        });
    }
    @AfterEach void cleanup()throws Exception{
        fx(()->{if(registration!=null)registration.dispose();WorkspaceMotion.cancelAll();if(home!=null)home.dynamicContentContainer.getChildren().clear();return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        if(previousProfile==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousProfile);
    }

    @Test void matchingOzonEntryUsesNativePaneAndRetainsCachedRegistrationView()throws Exception{
        fx(()->{
            home.showOzonRegistration(new Shop(first.getId(),first.getName(),first.getMarketplace(),first.getClientId(),first.getApiKey()));
            assertEquals("znack-registration",navigator.currentRoute());assertEquals(1,builds.get());
            var host=(StackPane)registrationView.lookup("#ozonRegistrationHost");assertNotNull(host);assertTrue(host.isVisible());
            var nativePane=assertInstanceOf(OzonRegistrationPane.class,host.getChildren().getFirst());
            assertEquals(first.getId(),((Shop)field(nativePane,"owner")).getId());
            home.showOzonRegistration(first);assertEquals(1,builds.get());assertSame(nativePane,host.getChildren().getFirst(),"Opening registration twice must not replace its active Ozon work");
            return null;
        });
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ozon_card_registrations")){assertEquals(0,r.getInt(1));}
    }

    @Test void staleCrossShopCredentialOrWbRequestsCannotNavigate()throws Exception{
        fx(()->{
            home.showOzonRegistration(null);home.showOzonRegistration(second);
            home.showOzonRegistration(new Shop(first.getId(),first.getName(),Marketplace.OZON,first.getClientId(),"fixture-old"));
            assertEquals(0,builds.get());assertEquals("",navigator.currentRoute());
            state.setSelectedShop(second);home.showOzonRegistration(first);assertEquals(0,builds.get());
            var wb=new Shop(3,"Synthetic WB",Marketplace.WILDBERRIES,"","fixture-wb");state.setSelectedShop(wb);home.showOzonRegistration(wb);assertEquals(0,builds.get());
            state.setSelectedShop(null);home.showOzonRegistration(first);assertEquals(0,builds.get());
            state.setSelectedShop(second);home.showOzonRegistration(second);assertEquals(1,builds.get());
            var host=(StackPane)registrationView.lookup("#ozonRegistrationHost");assertEquals(second.getId(),((Shop)field(host.getChildren().getFirst(),"owner")).getId());
            return null;
        });
    }
}
