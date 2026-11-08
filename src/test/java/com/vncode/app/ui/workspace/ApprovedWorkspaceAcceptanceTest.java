package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.ozon.OzonFbsPage;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.shop.ShopSidebarController;
import com.vncode.app.ui.products.ProductsPane;
import com.vncode.app.ui.ozon.OzonDashboardController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native production controls over isolated synthetic data; no marketplace writes. */
class ApprovedWorkspaceAcceptanceTest {
    @TempDir Path profile;
    private final Shop wb=new Shop(1,"Cửa hàng minh họa",Marketplace.WILDBERRIES,"","fixture");
    private final Shop ozon=new Shop(2,"Cửa hàng minh họa",Marketplace.OZON,"fixture","fixture");
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic WB','WILDBERRIES',NULL,'fixture'),(2,'Synthetic Ozon','OZON','fixture','fixture')");
            for(int n=1;n<=12;n++){
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,"+n+",'Костюм спортивный с капюшоном','DEMO-"+n+"','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+n+","+n+",'XL')");
            }
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(wb);
    }
    @AfterEach void cleanup()throws Exception {com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    static <T>T fx(Callable<T> work)throws Exception {var task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
    static void pulse()throws Exception {var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();done.complete(null);}}}.start();return null;});done.get(5,TimeUnit.SECONDS);}
    record View(Parent root,Runnable close) { }

    @Test void approvedChromeRetainsShopActionsAndNativePagesInBothThemes()throws Exception {
        var previous=I18nService.getInstance().getCurrentLanguage();var calls=new ArrayList<String>();
        var shell=new BorderPane[1];var content=new BorderPane[1];var head=new FXMLLoader[1];var side=new FXMLLoader[1];
        Stage stage=fx(()->{
            I18nService.getInstance().setLanguage(AppLanguage.VI);
            head[0]=FxmlViewLoader.loader(WorkspaceHeaderController.class,"workspace-header-view.fxml");
            side[0]=FxmlViewLoader.loader(ShopSidebarController.class,"shop-sidebar-view.fxml");
            Parent header=head[0].load(),nav=side[0].load();content[0]=new BorderPane();content[0].setTop(header);
            shell[0]=new BorderPane(content[0],null,null,null,nav);
            var window=new Stage();window.setScene(new Scene(shell[0],1440,900));ThemeService.applyTheme(window.getScene());window.show();
            ShopSidebarController sidebar=side[0].getController();sidebar.setViewportWidth(1440);
            WorkspaceHeaderController controller=head[0].getController();controller.setShops(List.of(wb,ozon),wb);
            controller.setOnEditShop(()->calls.add("edit"));controller.setOnAddShop(()->calls.add("add"));controller.setOnDeleteShop(()->calls.add("delete"));controller.setControls(true,false,true,true);
            return window;
        });
        try {
            for(String page:List.of("overview","products","ozon-fbs","wb-fbs","registration","gs1","znack-config","fbo","kiz","finance","history","templates")){
                View view=fx(()->{
                    ((WorkspaceHeaderController)head[0].getController()).setShops(List.of(wb,ozon),page.equals("ozon-fbs")?ozon:wb);
                    ((ShopSidebarController)side[0].getController()).selectNavigation(switch(page){case "products"->"productsButton";case "ozon-fbs","wb-fbs"->"packingButton";case "registration"->"znackRegistrationButton";case "gs1"->"gs1Button";case "znack-config"->"znackAutomationButton";case "fbo"->"fboPackingButton";case "kiz"->"kizMappingButton";case "finance"->"financeButton";case "history"->"btnPrintHistory";case "templates"->"templateButton";default->"dashboardButton";});
                    return page(page);
                });
                try {
                    fx(()->{content[0].setCenter(new WorkspaceViewport(view.root()));return null;});
                    com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();pulse();
                    for(String theme:List.of("dark","light")){
                        fx(()->{ThemeService.switchTheme(theme);return null;});pulse();
                        fx(()->{
                            shell[0].applyCss();shell[0].layout();
                            assertTrue(((Region)side[0].getRoot()).getWidth() >= 180
                                    && ((Region)side[0].getRoot()).getWidth() <= 240,"Expanded navigation must preserve the work area");
                            assertTrue(((Region)head[0].getRoot()).getHeight() >= 44
                                    && ((Region)head[0].getRoot()).getHeight() <= 76,"Header must stay compact and usable");
                            for(String id:List.of("manageShopButton","deleteShopButton")){
                                Node action=(Node)head[0].getNamespace().get(id);assertTrue(action.isVisible()&&action.isManaged());
                                var bounds=action.localToScene(action.getBoundsInLocal());assertTrue(bounds.getMaxX()<stage.getScene().getWidth());
                            }
                            assertFalse(((HBox)head[0].getRoot()).getChildren().contains(head[0].getNamespace().get("syncHealthLabel")),"Sync diagnostics belong in the jobs menu");
                            com.vncode.app.testsupport.UiEvidence.capture(stage.getScene(),"approved-"+page+"-"+theme);
                            if(page.equals("ozon-fbs")){
                                var table=(TableView<?>)view.root().lookup("#newOrdersTable");
                                long fullRows=table.lookupAll(".table-row-cell").stream().filter(n->n instanceof TableRow<?> row&&!row.isEmpty()&&n.localToScene(n.getBoundsInLocal()).getMaxY()<table.localToScene(table.getBoundsInLocal()).getMaxY()).count();
                                assertTrue(fullRows>=7,"Approved desktop must show at least seven full order rows, found "+fullRows);
                                assertTrue(table.lookupAll(".table-row-cell").stream().filter(n->n instanceof TableRow<?> row&&!row.isEmpty()).allMatch(n->n.getBoundsInLocal().getHeight()<90),"Single-item orders must stay compact");
                            }
                            return null;
                        });
                        if(page.equals("ozon-fbs")) {
                            for(var size:List.of(new int[]{1366,768},new int[]{1920,1080})) {
                                fx(()->{double frameX=stage.getWidth()-stage.getScene().getWidth(),frameY=stage.getHeight()-stage.getScene().getHeight();stage.setMinWidth(size[0]+frameX);stage.setMinHeight(size[1]+frameY);stage.setWidth(size[0]+frameX);stage.setHeight(size[1]+frameY);return null;});pulse();
                                fx(()->{assertEquals(size[0],stage.getScene().getWidth(),1);com.vncode.app.testsupport.UiEvidence.capture(stage.getScene(),"approved-ozon-"+size[0]+"-"+theme);return null;});
                            }
                            fx(()->{double frameX=stage.getWidth()-stage.getScene().getWidth(),frameY=stage.getHeight()-stage.getScene().getHeight();stage.setMinWidth(1440+frameX);stage.setMinHeight(900+frameY);stage.setWidth(1440+frameX);stage.setHeight(900+frameY);return null;});pulse();
                            fx(()->{
                                var table=(TableView<?>)view.root().lookup("#newOrdersTable");
                                table.requestFocus();table.getSelectionModel().select(1);
                                table.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.ENTER,false,false,false,false));return null;
                            });
                            var settled=new CompletableFuture<Void>();fx(()->{var wait=new javafx.animation.PauseTransition(javafx.util.Duration.millis(280));wait.setOnFinished(e->settled.complete(null));wait.play();return null;});settled.get(5,TimeUnit.SECONDS);
                            fx(()->{assertNotNull(view.root().lookup("#workspaceDetailDrawer"));com.vncode.app.testsupport.UiEvidence.capture(stage.getScene(),"approved-ozon-detail-"+theme);((Button)view.root().lookup("#closeWorkspaceDetail")).fire();return null;});
                            var closed=new CompletableFuture<Void>();fx(()->{var wait=new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));wait.setOnFinished(e->closed.complete(null));wait.play();return null;});closed.get(5,TimeUnit.SECONDS);
                        }
                    }
                } finally {fx(()->{view.close().run();content[0].setCenter(null);return null;});}
            }
            fx(()->{
                var manage=(MenuButton)head[0].getNamespace().get("manageShopButton");
                var add=(Button)head[0].getNamespace().get("addShopButton");
                var edit=(Button)head[0].getNamespace().get("editShopButton");
                assertFalse(((HBox)head[0].getRoot()).getChildren().contains(add),"Add shop belongs inside Manage");
                assertNull(((Parent)side[0].getRoot()).lookup("#addShopButton"),"Sidebar must not duplicate Add shop");
                manage.show();assertTrue(manage.isShowing());
                assertTrue(manage.getItems().stream().filter(CustomMenuItem.class::isInstance).map(CustomMenuItem.class::cast)
                        .map(CustomMenuItem::getContent).anyMatch(node->node==add||node instanceof Parent parent&&parent.lookup("#addShopButton")==add),"Manage must contain the Add action");
                assertTrue(add.isVisible()&&add.isManaged()&&!add.isDisabled());
                add.fire();assertFalse(manage.isShowing(),"Invoking Add must dismiss Manage");
                manage.show();edit.fire();assertFalse(manage.isShowing());
                ((Button)head[0].getNamespace().get("deleteShopButton")).fire();
                assertEquals(List.of("add","edit","delete"),calls,"Existing callbacks must fire exactly once");
                return null;
            });
        } finally {fx(()->{((WorkspaceHeaderController)head[0].getController()).dispose();stage.close();I18nService.getInstance().setLanguage(previous);return null;});}
    }

    private View page(String name)throws Exception {
        if(name.equals("products")){
            var rows=new ArrayList<ProductRow>();for(int n=1;n<=12;n++)rows.add(new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,Integer.toString(n),""),"Костюм спортивный с капюшоном","DEMO-"+n,Integer.toString(n),"Костюмы спортивные","Demo","Синий","XL",List.of("204400000000"+n),"","","Хлопок 80%, полиэстер 20%","6112110000",DocumentState.VALID,"Мужской",true));
            var pane=new ProductsPane(q->new ProductPage(rows,rows.size(),""),ShopReadCoordinator.shared());pane.setShop(wb);return new View(pane,pane::dispose);
        }
        if(name.equals("gs1")){var pane=new com.vncode.app.ui.gs1.Gs1Pane();return new View(pane,()->{});}
        if(name.equals("overview")){
            var item=new com.vncode.app.features.news.NewsItem("demo",java.time.Instant.parse("2026-11-04T00:00:00Z"),"Dữ liệu minh họa · In nhãn và phiếu nhặt hàng theo lô","Tìm đơn theo đúng mã vận đơn, giữ nguyên nhãn chính thức của sàn.");
            var news=new com.vncode.app.ui.news.NewsPane(new com.vncode.app.features.news.NewsService(List.of(item),new com.vncode.app.features.news.NewsRepository(profile.resolve("news"))),()->{},n->{});
            var pane=new com.vncode.app.ui.dashboard.WelcomePane(news.preview(),Collections.nCopies(6,()->{}));
            var dashboard=FxmlViewLoader.loader(com.vncode.app.MainApplication.class,"/com/vncode/app/ui/dashboard/dashboard-view.fxml");pane.setDashboard(dashboard.load());return new View(pane,news::dispose);
        }
        String path=switch(name){case "ozon-fbs"->"ozon/ozon-dashboard-view.fxml";case "wb-fbs"->"packing/packing-view.fxml";case "registration"->"znackregistration/znack-card-registration-view.fxml";case "znack-config"->"znack/znack-automation-view.fxml";case "fbo"->"fbo/fbo-packing-view.fxml";case "kiz"->"kizmapping/kiz-mapping-view.fxml";case "finance"->"finance/finance-dashboard-view.fxml";case "history"->"history/print-history-view.fxml";default->"print/print-template-designer-view.fxml";};
        var loader=FxmlViewLoader.loader(com.vncode.app.MainApplication.class,"/com/vncode/app/ui/"+path);Parent root=loader.load();Object controller=loader.getController();
        if(controller instanceof OzonDashboardController dashboard){
            dashboard.setFbsReader(q->{var rows=new ArrayList<OzonPostingDto>();for(int n=1;n<=12;n++)rows.add(new OzonPostingDto("DEMO-010"+n+"-1","","",q.status(),"","","2026-11-05T12:00:00+03:00","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1","1001","DEMO-"+n,"Костюм спортивный с капюшоном",1,"RUB","1290"))));return new OzonFbsPage(rows,rows.size(),Map.of());});dashboard.setShop(ozon,false);
        }
        if(controller instanceof com.vncode.app.ui.znackregistration.ZnackCardRegistrationController registration)registration.setShop(wb);
        if(name.equals("templates"))((ListView<?>)loader.getNamespace().get("elementListView")).getSelectionModel().select(0);
        if(name.equals("znack-config"))((Tab)loader.getNamespace().get("settingsTab")).getTabPane().getSelectionModel().select((Tab)loader.getNamespace().get("settingsTab"));
        return new View(root,()->{try{controller.getClass().getMethod("dispose").invoke(controller);}catch(NoSuchMethodException absent){}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}});
    }

    @Test void detailDrawerKeepsListIdentitySelectionAndRestoresFocus()throws Exception {
        var list=new ListView<String>();var host=new StackPane(list);var drawer=new WorkspaceDetailDrawer[1];
        Stage stage=fx(()->{
            WorkspaceMotion.setReduced(true);list.getItems().setAll("One","Two","Three");list.getSelectionModel().select(1);
            var window=new Stage();window.setScene(new Scene(host,800,600));ThemeService.applyTheme(window.getScene());window.show();
            drawer[0]=new WorkspaceDetailDrawer(host);list.requestFocus();
            drawer[0].show("One",List.of(new WorkspaceDetailDrawer.Section("Summary",List.of(new WorkspaceDetailDrawer.Field("Status","Details")))));
            assertSame(list,host.getChildren().getFirst());assertEquals(1,list.getSelectionModel().getSelectedIndex());
            drawer[0].close();return window;
        });
        try {
            // Reduced motion keeps a 40 ms opacity cue; closing still must finish and restore focus.
            var closed=new CompletableFuture<Void>();
            fx(()->{var wait=new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));wait.setOnFinished(e->closed.complete(null));wait.play();return null;});
            closed.get(5,TimeUnit.SECONDS);
            fx(()->{
                assertEquals(1,host.getChildren().size(),"Closing must remove the drawer completely");
                assertSame(list,host.getChildren().getFirst());assertEquals(1,list.getSelectionModel().getSelectedIndex());
                assertEquals(list,stage.getScene().getFocusOwner());
                drawer[0].show("Two",List.of(new WorkspaceDetailDrawer.Section("Summary",List.of(new WorkspaceDetailDrawer.Field("Status","New details")))));
                drawer[0].clear();assertEquals(1,host.getChildren().size());
                assertEquals(List.of("One","Two","Three"),list.getItems());return null;
            });
        } finally {fx(()->{drawer[0].clear();stage.close();return null;});}
    }

    @Test void unsavedZnackSettingsRemainScopedToTheirStoreAndCancelDoesNotWrite()throws Exception {
        fx(()->{
            WorkspaceMotion.setReduced(true);var loader=FxmlViewLoader.loader(com.vncode.app.MainApplication.class,"/com/vncode/app/ui/znack/znack-automation-view.fxml");loader.load();
            var controller=(com.vncode.app.ui.znack.ZnackAutomationController)loader.getController();
            controller.setShop(wb);((Button)loader.getNamespace().get("editSettingsButton")).fire();
            ((TextField)loader.getNamespace().get("omsIdField")).setText("synthetic-unsaved-first");
            controller.setShop(ozon);assertEquals("",((TextField)loader.getNamespace().get("omsIdField")).getText());
            ((Button)loader.getNamespace().get("editSettingsButton")).fire();((TextField)loader.getNamespace().get("omsIdField")).setText("synthetic-unsaved-second");
            controller.setShop(wb);assertEquals("synthetic-unsaved-first",((TextField)loader.getNamespace().get("omsIdField")).getText());
            ((Button)loader.getNamespace().get("editSettingsButton")).fire();assertEquals("",((TextField)loader.getNamespace().get("omsIdField")).getText());
            controller.setShop(ozon);assertEquals("synthetic-unsaved-second",((TextField)loader.getNamespace().get("omsIdField")).getText());
            controller.setShop(wb);assertEquals("",((TextField)loader.getNamespace().get("omsIdField")).getText());return null;
        });
    }

    @Test void reopeningDrawerDuringCloseKeepsTheNewDetailsVisible()throws Exception {
        var host=new StackPane[1];var drawer=new WorkspaceDetailDrawer[1];
        Stage stage=fx(()->{
            WorkspaceMotion.setReduced(false);host[0]=new StackPane(new ListView<>());
            var window=new Stage();window.setScene(new Scene(host[0],800,600));ThemeService.applyTheme(window.getScene());window.show();
            drawer[0]=new WorkspaceDetailDrawer(host[0]);
            drawer[0].show("First",List.of());drawer[0].close();
            drawer[0].show("Second",List.of(new WorkspaceDetailDrawer.Section("Details",List.of(new WorkspaceDetailDrawer.Field("Status","Current")))));
            return window;
        });
        try {
            var finished=new CompletableFuture<Void>();
            fx(()->{var wait=new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));wait.setOnFinished(e->finished.complete(null));wait.play();return null;});finished.get(5,TimeUnit.SECONDS);
            fx(()->{var panel=host[0].lookup("#workspaceDetailDrawer");assertNotNull(panel);assertEquals(1,panel.getOpacity());assertTrue(panel.lookupAll(".label").stream().anyMatch(n->n instanceof Label label&&label.getText().equals("Second")));return null;});
        } finally {fx(()->{drawer[0].clear();stage.close();return null;});}
    }
}
