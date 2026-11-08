package com.vncode.app.ui.workspace;

import com.vncode.app.MainApplication;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseService;
import com.vncode.app.features.news.*;
import com.vncode.app.features.ozon.OzonFbsPage;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.license.LicenseApi;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.testsupport.ShopReadTestCleanup;
import com.vncode.app.testsupport.UiEvidence;
import com.vncode.app.ui.ozon.OzonDashboardController;
import com.vncode.app.ui.products.ProductsPane;
import com.vncode.app.ui.shop.ShopSidebarController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native Linux JavaFX evidence over a disposable synthetic profile. These captures do not
 * represent seller data, marketplace acceptance, Windows/DPI behavior or license activation.
 * Opt in with -Dvncode.workbench.capture.dir=/workspace/vncode-native-glass-20261108.
 */
class NativeGlassEvidenceTest {
    @TempDir Path profile;
    private final Shop wb = new Shop(1,"Minh họa tổng hợp WB",Marketplace.WILDBERRIES,"","fixture");
    private final Shop ozon = new Shop(2,"Minh họa tổng hợp Ozon",Marketplace.OZON,"fixture","fixture");
    private final List<String> geometry = new ArrayList<>();
    private final List<String> captures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<String,Double> listHeights=new HashMap<>();
    private final AtomicInteger licenseCalls = new AtomicInteger();
    private int controlChecks;
    private LicenseService offlineLicense;
    private static final List<int[]> DESKTOPS = List.of(new int[]{1280,720}, new int[]{1366,768},
            new int[]{1440,900}, new int[]{1920,1080}, new int[]{2560,1440});
    private static final Set<String> KEY_PAGES = Set.of("welcome","products-wb","products-ozon",
            "packing","ozon","znack-registration","znack-registration-ozon");
    // Includes every production HomeController route, plus the other marketplace variants.
    private static final List<String> ROUTES = List.of("welcome","products-wb","products-ozon",
            "packing","ozon","znack-registration","znack-registration-ozon","license","designer",
            "guides","news","finance","supplies","fbo-packing","fbo-orders","kiz-mapping",
            "znack","print-history","gs1","tnved","listing-copy","product-edit","kiz-warehouse","gtin-sync","settings");
    private record Page(Parent root, String listId, Runnable close) { }

    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work); Platform.runLater(task); return task.get(25, TimeUnit.SECONDS);
    }
    private static void pulse() throws Exception {
        var done = new CompletableFuture<Void>();
        fx(() -> { new javafx.animation.AnimationTimer() {
            int frames;
            @Override public void handle(long now) { if (++frames == 3) { stop(); done.complete(null); } }
        }.start(); return null; });
        done.get(8, TimeUnit.SECONDS);
    }
    @BeforeEach void seed() throws Exception {
        System.setProperty("vncode.appdata.dir", profile.toString()); Database.initDatabase();
        fx(()->{ThemeService.initializeAppearance();return null;});
        try (var c=Database.getConnection(); var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic WB','WILDBERRIES',NULL,'fixture'),(2,'Synthetic Ozon','OZON','fixture','fixture')");
            for(int n=1;n<=24;n++) {
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,"+n+",'SYNTHETIC · Костюм спортивный с капюшоном','DEMO-"+n+"','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+n+","+n+",'XL')");
                s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,'"+n+"','DEMO-"+n+"','"+(1000+n)+"','SYNTHETIC · Костюм спортивный','fixture')");
            }
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(wb);
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(ozon);
        // The second visible WB row exercises the longer existing "Update WB" caption.
        try(var c=Database.getConnection();var statement=c.createStatement()) {
            statement.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,10,14177449,'Цвет','[\"Blue\"]')");
            statement.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,status,created_at,updated_at) VALUES(1,10,10,'04631993764370','PUBLISHED','fixture','fixture')");
        }
        var scope=com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,wb.getName())).getSettings());
        new com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository().bindScope(wb,10,scope);
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(
                new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("04631993764370","DEMO-10","Blue","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(wb),Instant.now())));
        LicenseApi rejectingApi=(LicenseApi)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{LicenseApi.class},(p,m,a)->{
            if(m.getName().equals("supportsNetworkDiagnostics"))return false;
            if(m.getName().equals("networkMode"))return LicenseApi.NetworkMode.AUTOMATIC;
            licenseCalls.incrementAndGet();throw new AssertionError("Native visual evidence must not request license API: "+m.getName());
        });
        offlineLicense=new LicenseService(rejectingApi,null,null,Clock.systemUTC(),System::nanoTime);
    }
    @AfterEach void cleanup() throws Exception {
        if(offlineLicense!=null)offlineLicense.close();
        ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }

    @Test void productionRoutesKeepNativeControlsAndLargeListsAcrossApprovedDesktopSizes() throws Exception {
        var originalLanguage=I18nService.getInstance().getCurrentLanguage();
        var originalTheme=ThemeService.getCurrentTheme();
        var side=new FXMLLoader[1];var head=new FXMLLoader[1];var center=new BorderPane[1];
        Stage stage=fx(()->{
            I18nService.getInstance().setLanguage(AppLanguage.VI);WorkspaceMotion.setReduced(true);
            side[0]=FxmlViewLoader.loader(ShopSidebarController.class,"shop-sidebar-view.fxml");
            head[0]=FxmlViewLoader.loader(WorkspaceHeaderController.class,"workspace-header-view.fxml");
            Parent nav=side[0].load(),header=head[0].load();
            var sidebarContainer=new StackPane(nav);sidebarContainer.setId("sidebarContainer");sidebarContainer.setMinHeight(0);
            BorderPane.setMargin(sidebarContainer,new javafx.geometry.Insets(0,10,0,0));
            var headerContainer=new StackPane(header);headerContainer.setId("headerContainer");
            BorderPane.setMargin(headerContainer,new javafx.geometry.Insets(0,0,8,0));
            center[0]=new BorderPane();center[0].setId("contentPane");center[0].setMinHeight(0);center[0].setTop(headerContainer);
            var notice=new Label("DỮ LIỆU TỔNG HỢP · Native JavaFX / Linux · Không gọi API seller / license");
            notice.setId("syntheticEvidenceNotice");notice.getStyleClass().add("text-muted");
            var root=new BorderPane(center[0],null,null,notice,sidebarContainer);
            root.getStyleClass().add("workspace-shell");root.setPadding(new javafx.geometry.Insets(8));
            var window=new Stage();window.setTitle("Synthetic native acceptance evidence");
            window.setScene(new Scene(root,1440,900));ThemeService.applyTheme(window.getScene());window.show();return window;
        });
        try {
            for(String route:ROUTES) {
                Page page=fx(()->createPage(route));
                try {
                    fx(()->{
                        page.root().getStyleClass().add("app-page");center[0].setCenter(new WorkspaceViewport(page.root()));
                        WorkspaceHeaderController header=head[0].getController();
                        header.setShops(List.of(wb,ozon),route.contains("ozon")||route.equals("ozon")?ozon:wb);
                        header.setControls(true,false,true,true);
                        ((ShopSidebarController)side[0].getController()).selectNavigation(navigationId(route));return null;
                    });
                    ShopReadTestCleanup.awaitSharedIdle();pulse();
                    for(String theme:List.of("light","dark")) {
                        fx(()->{ThemeService.switchTheme(theme);return null;});
                        // Cached pages must survive the reported large-to-small desktop history.
                        if(Set.of("znack-registration","znack-registration-ozon").contains(route)) {
                            resize(stage,2560,1440);fx(()->{((ShopSidebarController)side[0].getController()).setViewportWidth(2560);return null;});pulse();
                        }
                        for(int[] desktop:KEY_PAGES.contains(route)?DESKTOPS:List.of(new int[]{1440,900})) {
                            resize(stage,desktop[0],desktop[1]);
                            fx(()->{((ShopSidebarController)side[0].getController()).setViewportWidth(desktop[0]);return null;});pulse();
                            fx(()->{
                                String context=route+"-"+theme+"-"+desktop[0]+"x"+desktop[1];
                                Scene scene=stage.getScene();scene.getRoot().applyCss();scene.getRoot().layout();
                                assertEquals(desktop[0],scene.getWidth(),1,context+" exact scene width");
                                assertEquals(desktop[1],scene.getHeight(),1,context+" exact scene height");
                                inspectFixedControls(scene,context);inspectList(scene,page,route,context);
                                if(desktop[0]==1280&&Set.of("znack-registration","znack-registration-ozon").contains(route)) {
                                    var captions=com.vncode.app.testsupport.NativeRegistrationActionAssertions.assertFullyRendered((TableView<?>)page.root().lookup("#"+page.listId()),context);
                                    if(route.equals("znack-registration")) {
                                        assertTrue(captions.contains(I18nService.getInstance().tr("znack.registration.create")),"Fixture must exercise the native Create row action");
                                        assertTrue(captions.contains(I18nService.getInstance().tr("znack.registration.update_wb_short")),"Fixture must exercise the native Update WB row action");
                                    }
                                }
                                capture(scene,"native-"+context);return null;
                            });
                        }
                        if(Set.of("znack","packing","ozon","znack-registration","znack-registration-ozon").contains(route)) {
                            resize(stage,1440,900);pulse();captureTabs(stage,page,route,theme);
                        }
                    }
                } finally {
                    fx(()->{center[0].setCenter(null);page.close().run();return null;});ShopReadTestCleanup.awaitSharedIdle();
                }
            }
            var addCalls=new AtomicInteger();
            fx(()->{
                var manage=(MenuButton)head[0].getNamespace().get("manageShopButton");
                var add=(Button)head[0].getNamespace().get("addShopButton");
                var combo=(ComboBox<?>)head[0].getNamespace().get("shopComboBox");
                assertFalse(((HBox)head[0].getRoot()).getChildren().contains(add));
                assertNull(((Parent)side[0].getRoot()).lookup("#addShopButton"));
                var selectorBounds=combo.localToScene(combo.getBoundsInLocal());
                var manageBounds=manage.localToScene(manage.getBoundsInLocal());
                assertTrue(manageBounds.getMinX()>=selectorBounds.getMaxX()&&manageBounds.getMinX()-selectorBounds.getMaxX()<=20,"Manage must remain adjacent to the shop selector");
                ((WorkspaceHeaderController)head[0].getController()).setOnAddShop(addCalls::incrementAndGet);return null;
            });
            for(String theme:List.of("light","dark")) {
                fx(()->{ThemeService.switchTheme(theme);((MenuButton)head[0].getNamespace().get("manageShopButton")).show();return null;});pulse();
                fx(()->{
                    var manage=(MenuButton)head[0].getNamespace().get("manageShopButton");
                    var add=(Button)head[0].getNamespace().get("addShopButton");
                    assertTrue(manage.isShowing());assertTrue(add.getScene().getWindow().isShowing());
                    WorkspaceMotion.cancelAll();add.getScene().getRoot().applyCss();add.getScene().getRoot().layout();
                    UiEvidence.captureWindow(stage,"native-manage-popup-"+theme);captures.add("native-manage-popup-"+theme+".png");
                    manage.hide();return null;
                });
            }
            fx(()->{
                var manage=(MenuButton)head[0].getNamespace().get("manageShopButton");manage.show();
                ((Button)head[0].getNamespace().get("addShopButton")).fire();
                assertEquals(1,addCalls.get());assertFalse(manage.isShowing());
                assertEquals(2,((ComboBox<?>)head[0].getNamespace().get("shopComboBox")).getItems().size(),"Callback test must not mutate synthetic shops");return null;
            });
            assertEquals(0,licenseCalls.get(),"Evidence must stay offline");
            assertTrue(controlChecks>500,"Evidence must inspect real controls across the matrix");
            writeEvidence();
            assertTrue(findings.isEmpty(),"Native geometry regressions:\n"+String.join("\n",findings));
        } finally {
            writeEvidence();
            fx(()->{((WorkspaceHeaderController)head[0].getController()).dispose();stage.close();
                I18nService.getInstance().setLanguage(originalLanguage);ThemeService.setCurrentTheme(originalTheme);return null;});
        }
    }

    private static void resize(Stage stage,int width,int height) throws Exception {
        fx(()->{
            stage.setMinWidth(0);stage.setMinHeight(0);
            double decorationWidth=stage.getWidth()-stage.getScene().getWidth(),decorationHeight=stage.getHeight()-stage.getScene().getHeight();
            stage.setWidth(width+decorationWidth);stage.setHeight(height+decorationHeight);return null;
        });pulse();
    }
    private void captureTabs(Stage stage,Page page,String route,String theme) throws Exception {
        var panes=fx(()->page.root().lookupAll(".tab-pane").stream().filter(TabPane.class::isInstance).map(TabPane.class::cast).toList());
        for(int paneIndex=0;paneIndex<panes.size();paneIndex++) {
            var tabs=panes.get(paneIndex);var selected=fx(()->tabs.getSelectionModel().getSelectedItem());
            for(int tabIndex=0;tabIndex<tabs.getTabs().size();tabIndex++) {
                final int index=tabIndex;fx(()->{tabs.getSelectionModel().select(index);return null;});pulse();
                final String state=route+"-"+theme+"-tabs"+paneIndex+"-"+tabIndex;
                fx(()->{inspectFixedControls(stage.getScene(),state);capture(stage.getScene(),"native-"+state);return null;});
            }
            fx(()->{tabs.getSelectionModel().select(selected);return null;});
        }
    }
    private void inspectFixedControls(Scene scene,String context) {
        for(Node node:scene.getRoot().lookupAll("*")) {
            if(!(node instanceof ButtonBase||node instanceof ComboBoxBase<?>||node instanceof TextInputControl)||!visible(node))continue;
            boolean insideScrollingContent=false;
            for(Node ancestor=node.getParent();ancestor!=null;ancestor=ancestor.getParent()) {
                if(ancestor instanceof TableView<?>||ancestor instanceof ListView<?>||ancestor instanceof TreeView<?>)insideScrollingContent=true;
                if(ancestor instanceof ScrollPane&&!(ancestor instanceof WorkspaceViewport))insideScrollingContent=true;
            }
            if(insideScrollingContent)continue;
            controlChecks++;Bounds b=node.localToScene(node.getBoundsInLocal());
            if(b.getMinX()<-1||b.getMinY()<-1||b.getMaxX()>scene.getWidth()+1||b.getMaxY()>scene.getHeight()+1)
                findings.add(context+" clipped control "+node.getId()+" "+b);
            if(node instanceof Labeled labeled&&!labeled.isDisabled()&&labeled.getOpacity()<.99)
                findings.add(context+" text-bearing action faded: "+node.getId());
        }
    }
    private static boolean visible(Node node) {
        for(Node ancestor=node;ancestor!=null;ancestor=ancestor.getParent())if(!ancestor.isVisible()||!ancestor.isManaged())return false;
        return node.getScene()!=null;
    }
    private void inspectList(Scene scene,Page page,String route,String context) {
        if(page.listId()==null)return;
        Node list=page.root().lookup("#"+page.listId());assertNotNull(list,context+" primary list missing");
        Bounds b=list.localToScene(list.getBoundsInLocal());
        Bounds pageBounds=page.root().localToScene(page.root().getBoundsInLocal());
        geometry.add(context+" list="+Math.round(b.getWidth())+"x"+Math.round(b.getHeight())+" scene="+Math.round(scene.getWidth())+"x"+Math.round(scene.getHeight()));
        if(b.getMinX()<-1||b.getMinY()<-1||b.getMaxX()>scene.getWidth()+1||b.getMaxY()>scene.getHeight()+1)
            findings.add(context+" primary list outside viewport: "+b);
        if(b.getWidth()<pageBounds.getWidth()*.88)findings.add(context+" primary list wastes working width: "+b);
        if(KEY_PAGES.contains(route)&&b.getHeight()<scene.getHeight()*.34)
            findings.add(context+" primary list wastes working height: "+b);
        String theme=scene.getRoot().getStyleClass().contains("theme-light")?"light":"dark";
        if(scene.getWidth()==1440)listHeights.put(route+theme,b.getHeight());
        if(scene.getWidth()==2560&&listHeights.containsKey(route+theme)&&b.getHeight()-listHeights.get(route+theme)<400)
            findings.add(context+" primary list fails to grow with a larger desktop");
    }
    private void capture(Scene scene,String name) throws Exception { WorkspaceMotion.cancelAll();UiEvidence.capture(scene,name);captures.add(name+".png"); }
    private void writeEvidence() throws Exception {
        String directory=System.getProperty("vncode.workbench.capture.dir");if(directory==null)return;
        Path folder=Path.of(directory);Files.createDirectories(folder);
        Files.writeString(folder.resolve("NATIVE-EVIDENCE.txt"),"Synthetic native Linux JavaFX evidence; no seller API or license activation.\n"
                +"Not Windows/DPI/real seller acceptance.\nRoutes: "+String.join(", ",ROUTES)+"\nControls checked: "+controlChecks
                +"\nCaptures: "+captures.size()+"\nLicense API calls: "+licenseCalls.get()+"\n\nGeometry:\n"+String.join("\n",geometry)
                +"\n\nFailures (zero required):\n"+String.join("\n",findings)+"\n\nFiles:\n"+String.join("\n",captures)+"\n");
    }
    private static String navigationId(String route) {
        return switch(route) {
            case "products-wb","products-ozon","product-edit"->"productsButton";
            case "packing","ozon"->"packingButton";
            case "znack-registration","znack-registration-ozon"->"znackRegistrationButton";
            case "designer"->"templateButton";case "guides"->"guidesButton";case "finance"->"financeButton";
            case "supplies"->"";case "fbo-packing"->"fboPackingButton";case "fbo-orders"->"fboOrdersButton";
            case "kiz-mapping","kiz-warehouse"->"kizMappingButton";case "znack"->"znackAutomationButton";
            case "print-history"->"btnPrintHistory";case "gs1"->"gs1Button";case "tnved"->"tnvedButton";
            case "listing-copy"->"listingCopyButton";case "settings"->"settingsMenuButton";default->"dashboardButton";
        };
    }
    private Page createPage(String route) throws Exception {
        if(route.equals("supplies")) {
            var managementLoader=FxmlViewLoader.loader(com.vncode.app.ui.supply.SupplyManagementController.class,"supply-management-view.fxml");
            Parent root=managementLoader.load();com.vncode.app.ui.supply.SupplyManagementController host=managementLoader.getController();
            var listLoader=FxmlViewLoader.loader(com.vncode.app.ui.supply.SupplyListController.class,"supply-list-view.fxml");
            Parent list=listLoader.load();com.vncode.app.ui.supply.SupplyListController listController=listLoader.getController();
            listController.applyTranslations();listController.setSupplies(List.of(new com.vncode.app.integration.wb.WbSupplySummary("SYNTHETIC-101","Shipment minh họa",false,false,"2026-11-08T10:00:00Z",24)));
            var detailLoader=FxmlViewLoader.loader(com.vncode.app.ui.supply.SupplyDetailController.class,"supply-detail-view.fxml");
            Parent detail=detailLoader.load();com.vncode.app.ui.supply.SupplyDetailController detailController=detailLoader.getController();
            detailController.applyTranslations();detailController.setSupplyInfo("SYNTHETIC-101 · Shipment minh họa","Dữ liệu tổng hợp · 24 đơn");
            var orders=new ArrayList<Order>();for(int n=1;n<=24;n++){var order=new Order();order.setId((long)n);order.setName("SYNTHETIC · Костюм спортивный");order.setArticle("DEMO-"+n);order.setBrand("Demo");order.setSize("XL");orders.add(order);}
            detailController.setOrders(orders);host.supplyListContainer.getChildren().setAll(list);host.supplyDetailContainer.getChildren().setAll(detail);
            assertNotNull(root.lookup("#orderTable"),"The route must compose the real supply child views, as Home does");
            return new Page(root,null,detailController::dispose);
        }
        if(route.startsWith("products-")) {
            Shop owner=route.endsWith("ozon")?ozon:wb;var rows=new ArrayList<ProductRow>();
            for(int n=1;n<=24;n++)rows.add(new ProductRow(new ProductKey(owner.getId(),owner.getMarketplace(),Integer.toString(n),""),
                    "SYNTHETIC · Костюм спортивный с капюшоном","DEMO-"+n,Integer.toString(n),"Костюмы спортивные","Demo","Синий","XL",
                    List.of("204400000000"+n),"","","Хлопок 80%, полиэстер 20%","6112110000",DocumentState.VALID,"Мужской",true));
            var products=new ProductsPane(q->new ProductPage(rows,rows.size(),""),ShopReadCoordinator.shared());products.setShop(owner);
            return new Page(products,"productRows",products::dispose);
        }
        if(route.equals("znack-registration-ozon")) {
            var registration=new com.vncode.app.ui.znackregistration.OzonRegistrationPane(ozon,()->{});
            return new Page(registration,"ozonRegistrationTable",registration::dispose);
        }
        if(route.equals("settings")) {
            var appearance=new WorkspaceAppearancePane();
            appearance.setOnTranslationSettings(()->{});appearance.setOnCheckUpdate(()->{});appearance.setOnAccount(()->{});
            appearance.setOnPrinterSettings(()->{});appearance.setOnAbout(()->{});return simple(appearance);
        }
        if(route.equals("license"))return simple(new com.vncode.app.ui.license.LicensePane(offlineLicense));
        if(route.equals("tnved"))return simple(new com.vncode.app.ui.tnved.FashionTnvedBrowser());
        if(route.equals("guides"))return simple(new com.vncode.app.ui.guides.GuidesPane());
        if(route.equals("listing-copy"))return simple(new com.vncode.app.ui.listingcopy.ListingCopyPane());
        if(route.equals("product-edit"))return simple(new com.vncode.app.ui.products.ProductBulkEditPane());
        if(route.equals("gs1"))return simple(new com.vncode.app.ui.gs1.Gs1Pane());
        if(route.equals("kiz-warehouse"))return simple(new com.vncode.app.ui.kiz.KizWarehousePane());
        if(route.equals("news")||route.equals("welcome")) {
            var item=new NewsItem("synthetic",Instant.parse("2026-11-04T00:00:00Z"),"Dữ liệu tổng hợp · In nhãn và phiếu nhặt hàng theo lô","Bản kiểm tra bố cục, không phải tin seller thật.");
            var news=new com.vncode.app.ui.news.NewsPane(new NewsService(List.of(item),new NewsRepository(profile.resolve("news"))),()->{},n->{});
            if(route.equals("news"))return new Page(news,null,news::dispose);
            var welcome=new com.vncode.app.ui.dashboard.WelcomePane(news.preview(),Collections.nCopies(6,()->{}));
            welcome.setDashboard(FxmlViewLoader.loader(MainApplication.class,"/com/vncode/app/ui/dashboard/dashboard-view.fxml").load());
            return new Page(welcome,null,news::dispose);
        }
        String path=switch(route) {
            case "packing"->"packing/packing-view.fxml";case "ozon"->"ozon/ozon-dashboard-view.fxml";
            case "znack-registration"->"znackregistration/znack-card-registration-view.fxml";
            case "designer"->"print/print-template-designer-view.fxml";case "finance"->"finance/finance-dashboard-view.fxml";
            case "supplies"->"supply/supply-management-view.fxml";case "fbo-packing"->"fbo/fbo-packing-view.fxml";
            case "fbo-orders"->"fbosupply/fbo-supply-orders-view.fxml";case "kiz-mapping"->"kizmapping/kiz-mapping-view.fxml";
            case "znack"->"znack/znack-automation-view.fxml";case "print-history"->"history/print-history-view.fxml";
            case "gtin-sync"->"gtinsync/gtin-sync-view.fxml";default->throw new IllegalArgumentException(route);
        };
        var loader=FxmlViewLoader.loader(MainApplication.class,"/com/vncode/app/ui/"+path);Parent root=loader.load();Object controller=loader.getController();
        if(controller instanceof OzonDashboardController dashboard) {
            dashboard.setFbsReader(q->{var rows=new ArrayList<OzonPostingDto>();for(int n=1;n<=24;n++)rows.add(new OzonPostingDto("DEMO-010"+n+"-1","","",q.status(),"","","2026-11-05T12:00:00+03:00","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1","1001","DEMO-"+n,"SYNTHETIC · Костюм спортивный",1,"RUB","1290"))));return new OzonFbsPage(rows,rows.size(),Map.of());});
            dashboard.setShop(ozon,false);
        }
        if(controller instanceof com.vncode.app.ui.znackregistration.ZnackCardRegistrationController registration)registration.setShop(wb);
        if(route.equals("packing")) {
            @SuppressWarnings("unchecked")var orders=(TableView<Order>)loader.getNamespace().get("newOrdersTable");
            for(int n=1;n<=24;n++){var order=new Order();order.setId((long)n);order.setName("SYNTHETIC · Костюм спортивный");order.setArticle("DEMO-"+n);order.setBrand("Demo");order.setSize("XL");orders.getItems().add(order);}
        }
        if(route.equals("designer"))((ListView<?>)loader.getNamespace().get("elementListView")).getSelectionModel().select(0);
        return new Page(root,switch(route){case "packing","ozon"->"newOrdersTable";case "znack-registration"->"productTable";default->null;},()->dispose(controller));
    }
    private static Page simple(Parent root) {return new Page(root,null,()->dispose(root));}
    private static void dispose(Object controller) {
        try {controller.getClass().getMethod("dispose").invoke(controller);}
        catch(NoSuchMethodException absent) { }
        catch(ReflectiveOperationException failure) {throw new IllegalStateException(failure);}
    }
}
