package com.vncode.app.ui.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.OzonFbsPage;
import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.packing.PackingController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
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

/** Native WB is the visual reference; synthetic rows never call marketplace APIs. */
class OzonWbPackingParityTest {
    @TempDir Path profile;
    private FXMLLoader wb,ozon;
    private Stage wbWindow,ozonWindow;
    private OzonDashboardController dashboard;
    private AppLanguage previousLanguage;
    private String previousProfile;
    private final List<com.vncode.app.features.ozon.OzonFbsQuery> observedQueries = new CopyOnWriteArrayList<>();
    private final List<String> orderTabs=List.of("newOrdersTab","packingOrdersTab","printedOrdersTab","deliveringOrdersTab");
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    private static <T>T fx(Callable<T> work)throws Exception{
        var task=new FutureTask<T>(work);Platform.runLater(task);
        try{return task.get(20,TimeUnit.SECONDS);}catch(ExecutionException failure){if(failure.getCause() instanceof AssertionError assertion)throw assertion;throw failure;}
    }
    private static void pulse()throws Exception{
        var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();done.complete(null);}}}.start();return null;});done.get(10,TimeUnit.SECONDS);
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
        previousProfile=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic WB','WILDBERRIES','','fixture-wb'),(2,'Synthetic Ozon','OZON','22','fixture-ozon')");
        }
        fx(()->{
            previousLanguage=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);
            wb=FxmlViewLoader.loader(PackingController.class,"packing-view.fxml");Parent wbRoot=wb.load();
            ozon=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");Parent ozonRoot=ozon.load();dashboard=ozon.getController();
            var item=new OzonPostingItemDto(0,"101","1001","152-NAU-2XL","Костюм спортивный мужской",2,"RUB","100");
            var product=new OzonProductDto("101","152-NAU-2XL","1001","Костюм спортивный мужской","",false,"",List.of("OZN1000001","OZN1000002")).withCardAttributes("152 NAU","коричневый","2XL","Костюмы спортивные","");
            dashboard.setFbsReader(query->{ observedQueries.add(query); return new OzonFbsPage(List.of(new OzonPostingDto("90000000-0001-1","1","90000000-0001",query.status(),"","","2026-10-25T12:00:00+03:00","","","",new OzonRequirements(List.of("101"),List.of(),List.of()),List.of(),false,List.of(item))),1,Map.of("1001",product),Map.of("1001","04631993764370"),Map.of("90000000-0001-1",List.of(new Kn3Mark("90000000-0001-1","1001","1","synthetic",Kn3Mark.State.ACCEPTED,""))));});
            dashboard.setShop(new Shop(2,"Synthetic Ozon",Marketplace.OZON,"22","fixture-ozon"),false);
            var order=new Order();order.setId(900000000001L);order.setName("Костюм спортивный мужской");order.setArticle("152 NAU");order.setBrand("Synthetic");order.setSize("2XL");((TableView<Order>)wb.getNamespace().get("newOrdersTable")).getItems().setAll(order);
            wbWindow=window(wbRoot);ozonWindow=window(ozonRoot);return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();pulse();
        fx(()->{wbWindow.getScene().getRoot().applyCss();wbWindow.getScene().getRoot().layout();ozonWindow.getScene().getRoot().applyCss();ozonWindow.getScene().getRoot().layout();return null;});
    }
    private static Stage window(Parent root){var stage=new Stage();var scene=new Scene(root,1200,720);ThemeService.applyTheme(scene);stage.setScene(scene);stage.show();double dx=stage.getWidth()-scene.getWidth(),dy=stage.getHeight()-scene.getHeight();stage.setWidth(1200+dx);stage.setHeight(720+dy);return stage;}
    @AfterEach void cleanup()throws Exception{
        fx(()->{if(dashboard!=null)dashboard.dispose();if(wb!=null)((PackingController)wb.getController()).dispose();if(wbWindow!=null)wbWindow.close();if(ozonWindow!=null)ozonWindow.close();if(previousLanguage!=null)I18nService.getInstance().setLanguage(previousLanguage);return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();if(previousProfile==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousProfile);
    }
    private <T>T wb(String name,Class<T> type){return type.cast(wb.getNamespace().get(name));}
    private <T>T ozon(String name,Class<T> type){return type.cast(ozon.getNamespace().get(name));}
    private static Bounds bounds(Node node){return node.localToScene(node.getLayoutBounds());}
    private static boolean within(Node node,Node ancestor){for(Node p=node;p!=null;p=p.getParent())if(p==ancestor)return true;return false;}
    private static boolean visible(Node node){for(Node p=node;p!=null;p=p.getParent())if(!p.isVisible())return false;return true;}

    @Test void workspaceSpacingAndInsetsMatchNativeWbPacking()throws Exception{fx(()->{
        var expected=(VBox)wb.getRoot();var actual=(VBox)ozon.getRoot();
        assertEquals(expected.getPadding(),actual.getPadding());assertEquals(expected.getSpacing(),actual.getSpacing(),.01,"Ozon must use the actual WB FBS vertical rhythm, not the supply detail preset");return null;
    });}
    @Test void refreshSitsBesideTitleLikeNativeWbPacking()throws Exception{fx(()->{
        var expectedTitle=bounds(wb("titleLabel",Label.class));var expectedRefresh=bounds(wb("refreshButton",Button.class));
        var actualTitle=bounds(ozon("titleLabel",Label.class));var actualRefresh=bounds(ozon("refreshButton",Button.class));
        assertEquals(expectedRefresh.getMinX()-expectedTitle.getMaxX(),actualRefresh.getMinX()-actualTitle.getMaxX(),2,"Refresh belongs next to the title, while Ozon utilities keep their independent actions");
        assertEquals(expectedRefresh.getMinY(),actualRefresh.getMinY(),2);return null;
    });}
    @Test void orderFiltersFollowTheTabStripLikeNativeWbPacking()throws Exception{fx(()->{
        var wbFilter=bounds(wb("newOrderSearchField",TextField.class));var wbTabs=bounds(wb("packingTabPane",TabPane.class));
        var ozonFilter=bounds(ozon("postingSearch",TextField.class));var ozonTabs=bounds(ozon("orderStatusTabs",TabPane.class));
        assertTrue(wbFilter.getMinY()>wbTabs.getMinY());assertTrue(ozonFilter.getMinY()>ozonTabs.getMinY(),"Ozon currently places search above the tabs, unlike WB");
        assertEquals(wbFilter.getMinX(),ozonFilter.getMinX(),2);return null;
    });}
    @Test void primaryOrderTableUsesTheSameFiveColumnsAndWidths()throws Exception{fx(()->{
        var expected=wb("newOrdersTable",TableView.class);var actual=ozon("newOrdersTable",TableView.class);
        assertEquals(expected.getVisibleLeafColumns().size(),actual.getVisibleLeafColumns().size(),"STT and barcode details belong within the WB-style primary cells, not competing standalone columns");
        var a=expected.getVisibleLeafColumns();var b=actual.getVisibleLeafColumns();
        String diagnostics=" WB table="+expected.getWidth()+" policy="+expected.getColumnResizePolicy()+" columns="+a.stream().map(c->{var column=(TableColumn<?,?>)c;return column.getText()+":"+column.getWidth()+"/pref="+column.getPrefWidth()+"/min="+column.getMinWidth()+"/max="+column.getMaxWidth()+"/resizable="+column.isResizable();}).toList()+" OZON table="+actual.getWidth()+" policy="+actual.getColumnResizePolicy()+" columns="+b.stream().map(c->{var column=(TableColumn<?,?>)c;return column.getText()+":"+column.getWidth()+"/pref="+column.getPrefWidth()+"/min="+column.getMinWidth()+"/max="+column.getMaxWidth()+"/resizable="+column.isResizable();}).toList();
        String metricsFile=System.getProperty("vncode.ui.parity.metrics.file");
        if(metricsFile!=null)java.nio.file.Files.writeString(Path.of(metricsFile),diagnostics+"\n");
        for(int n=0;n<a.size();n++)assertEquals(((TableColumn<?,?>)a.get(n)).getWidth(),((TableColumn<?,?>)b.get(n)).getWidth(),2,"Rendered column "+n+" must use WB's full-width layout policy"+diagnostics);return null;
    });}
    @Test void orderColumnSpecificationsMatchTheWbReference()throws Exception{fx(()->{
        String[][] pairs={{"selectedTC","newOrderSelectTC"},{"orderDateTC","newOrderNumberTC"},{"imageTC","newOrderImageTC"},{"productTC","newOrderItemsTC"},{"priceTC","newOrderPriceTC"}};
        for(var pair:pairs){var expected=wb(pair[0],TableColumn.class);var actual=ozon(pair[1],TableColumn.class);assertEquals(expected.getMinWidth(),actual.getMinWidth(),.01,pair[1]+" minimum");assertEquals(expected.getPrefWidth(),actual.getPrefWidth(),.01,pair[1]+" preferred");}return null;
    });}
    private static <T extends Node>T ancestor(Node node,Class<T> type){
        for(Node parent=node.getParent();parent!=null;parent=parent.getParent())if(type.isInstance(parent))return type.cast(parent);
        throw new AssertionError("Missing rendered ancestor "+type.getSimpleName());
    }
    private static Map<String,Object> thumbnailGeometry(Node thumbnail){
        var cell=ancestor(thumbnail,TableCell.class);var row=ancestor(thumbnail,TableRow.class);
        var metric=new LinkedHashMap<String,Object>();metric.put("thumbnail",geometry(thumbnail));metric.put("cell",geometry(cell));metric.put("row",geometry(row));
        metric.put("relativeToCell",List.of(bounds(thumbnail).getMinX()-bounds(cell).getMinX(),bounds(thumbnail).getMinY()-bounds(cell).getMinY()));
        metric.put("relativeToRow",List.of(bounds(thumbnail).getMinX()-bounds(row).getMinX(),bounds(thumbnail).getMinY()-bounds(row).getMinY()));
        var chain=new ArrayList<Map<String,Object>>();for(Node node=thumbnail;node!=null;node=node.getParent()){chain.add(geometry(node));if(node==row)break;}metric.put("ancestorChain",chain);return metric;
    }
    @Test void productThumbnailsUseWbFbsDimensions()throws Exception{fx(()->{
        Node expected=wb("newOrdersTable",TableView.class).lookup(".image-placeholder");Node actual=ozon("newOrdersTable",TableView.class).lookup(".image-placeholder");assertNotNull(expected);assertNotNull(actual);
        var metric=new LinkedHashMap<String,Object>();metric.put("WB",thumbnailGeometry(expected));metric.put("Ozon",thumbnailGeometry(actual));
        String file=System.getProperty("vncode.ui.parity.thumbnail.metrics.file",profile.resolve("thumbnail-geometry.json").toString());
        java.nio.file.Files.writeString(Path.of(file),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(metric));
        var expectedCell=bounds(ancestor(expected,TableCell.class));var actualCell=bounds(ancestor(actual,TableCell.class));
        var expectedRow=bounds(ancestor(expected,TableRow.class));var actualRow=bounds(ancestor(actual,TableRow.class));
        var a=bounds(expected);var b=bounds(actual);
        assertAll("Photo size and actual position match native WB FBS",
            ()->assertEquals(expected.getLayoutBounds().getWidth(),actual.getLayoutBounds().getWidth(),.1),
            ()->assertEquals(expected.getLayoutBounds().getHeight(),actual.getLayoutBounds().getHeight(),.1),
            ()->assertEquals(a.getMinX()-expectedCell.getMinX(),b.getMinX()-actualCell.getMinX(),.5,"Photo X relative to its image cell"),
            ()->assertEquals(a.getMinY()-expectedCell.getMinY(),b.getMinY()-actualCell.getMinY(),.5,"Photo Y relative to its image cell"),
            ()->assertEquals(a.getMinX()-expectedRow.getMinX(),b.getMinX()-actualRow.getMinX(),.5,"Photo X relative to its order row"),
            ()->assertEquals(a.getMinY()-expectedRow.getMinY(),b.getMinY()-actualRow.getMinY(),.5,"Photo Y relative to its order row"));return null;
    });}
    private static String renderedEvidence(TableView<?> table) {
        var evidence = new StringBuilder();
        for (Node node : table.lookupAll("*")) {
            if (!visible(node)) continue;
            if (node.getAccessibleText() != null) evidence.append('\n').append(node.getAccessibleText());
            if (node instanceof Label label) {
                if (label.getText() != null) evidence.append('\n').append(label.getText());
                if (label.getTooltip() != null) evidence.append('\n').append(label.getTooltip().getText());
            }
        }
        return evidence.toString();
    }
    @Test void everyOzonStateKeepsIdentifiersAndMarkingEvidenceAccessible()throws Exception{
        for(String tabName:orderTabs){fx(()->{ozon("orderStatusTabs",TabPane.class).getSelectionModel().select(ozon(tabName,Tab.class));return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();pulse();
            fx(()->{String tableName=tabName.equals("newOrdersTab")?"newOrdersTable":tabName.replace("Tab","Table");TableView<?> table=ozon(tableName,TableView.class);table.applyCss();table.layout();
                String evidence = renderedEvidence(table);
                for(String value:List.of("90000000-0001","90000000-0001-1","25.10.2026","12:00 +03:00","152 NAU","коричневый","2XL","Костюмы спортивные","OZN1000001","OZN1000002","04631993764370","SKU: 1001","KN3","× 2"))assertTrue(evidence.contains(value),tabName+" must preserve accessible row information: "+value);
                boolean visibleIndexColumn = table.getVisibleLeafColumns().stream().anyMatch(column -> "ozonRowNumber".equals(column.getId()));
                assertTrue(evidence.contains("STT: 1") || visibleIndexColumn, tabName+" preserves the actual page row number through the order tooltip or original column");
                return null;});
        }
    }
    @Test void existingOzonActionsAndStateTabsRemainWired()throws Exception{fx(()->{
        for(String id:List.of("selectFilteredButton","clearBatchSelectionButton","cancelSelectionButton","moveToPackingButton","previousPageButton","nextPageButton","printAllButton","kizInventoryButton","ozonZnackRegistrationButton"))assertNotNull(ozon(id,Button.class).getOnAction(),id+" must keep its actual handler");
        var tabs=ozon("orderStatusTabs",TabPane.class);assertEquals(5,tabs.getTabs().size());for(String id:orderTabs)assertTrue(tabs.getTabs().contains(ozon(id,Tab.class)));assertTrue(tabs.getTabs().contains(ozon("shipmentKizTab",Tab.class)));return null;
    });}
    @Test void oneSharedSearchRemainsInsideEveryActiveOrderTab()throws Exception{
        fx(()->{ozon("postingSearch",TextField.class).setText("152 NAU");return null;});
        for(String id:orderTabs){fx(()->{var tab=ozon(id,Tab.class);ozon("orderStatusTabs",TabPane.class).getSelectionModel().select(tab);return null;});pulse();fx(()->{assertTrue(within(ozon("postingSearch",TextField.class),ozon(id,Tab.class).getContent()),id+" must host the same shared search below its own tab header");assertEquals(1,((Parent)ozon.getRoot()).lookupAll("#postingSearch").size());assertEquals("152 NAU",ozon("postingSearch",TextField.class).getText(),"Tab changes retain the shared filter text");return null;});}
    }
    private static TableRow<?> firstPostingRow(TableView<?> table) {
        return table.lookupAll(".table-row-cell").stream()
                .filter(node -> node instanceof TableRow<?> row && !row.isEmpty())
                .map(node -> (TableRow<?>) node).findFirst().orElseThrow();
    }
    @Test void nonemptySingleItemRowsMatchTheActualWbRowHeight() throws Exception {
        fx(() -> {
            var expected=firstPostingRow(wb("newOrdersTable",TableView.class));
            var actual=firstPostingRow(ozon("newOrdersTable",TableView.class));
            assertEquals(expected.getHeight(),actual.getHeight(),.5,"Rendered Ozon row density must match the actual WB FBS row");
            return null;
        });
    }
    @Test void multipleItemDetailsPhotosAndPricesStayInsideTheirOwnRow() throws Exception {
        fx(() -> {
            dashboard.setFbsReader(query -> new OzonFbsPage(List.of(new OzonPostingDto("fixture-multi","","",query.status(),"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(
                    new OzonPostingItemDto(0,"1","11","fixture-a","First complete product title",1,"RUB","100"),
                    new OzonPostingItemDto(1,"2","22","fixture-b","Second complete product title",2,"RUB","200")))),1,Map.of()));
            dashboard.refreshLocalData();
            return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();pulse();
        fx(() -> {
            var table=ozon("newOrdersTable",TableView.class);table.applyCss();table.layout();
            var row=firstPostingRow(table);var rowBounds=bounds(row);
            var titles=row.lookupAll(".text-bold").stream().filter(node -> node instanceof Label label && label.getText().endsWith("complete product title")).toList();
            assertEquals(2,titles.size(),"Both actual product titles remain rendered");
            var photos=row.lookupAll(".image-placeholder");assertEquals(2,photos.size(),"Each item retains its photo slot");
            var prices=row.lookupAll(".label").stream().filter(node -> node instanceof Label label && label.getText().endsWith("RUB")).toList();assertEquals(2,prices.size(),"Each item retains its own price");
            var retained=new ArrayList<Node>();retained.addAll(titles);retained.addAll(photos);retained.addAll(prices);
            retained.addAll(row.lookupAll(".label").stream().filter(OzonWbPackingParityTest::visible).toList());
            for(Node node:retained){var b=bounds(node);assertTrue(b.getMinY()>=rowBounds.getMinY()-1&&b.getMaxY()<=rowBounds.getMaxY()+1,"Every product line, price and photo fits its own row: "+node);}
            for(Node node:prices)assertTrue(bounds(node).getMaxX()<=bounds(table).getMaxX()-1,"Each full price remains inside the table viewport");
            return null;
        });
    }
    private static List<String> sortIds() {
        return List.of("sortByProductCheckBox", "sortByArticleCheckBox", "sortByColorCheckBox", "sortBySizeCheckBox");
    }
    @Test void nativeSortPopupOpensByKeyboardAndUsesTheActiveTheme() throws Exception {
        var previousTheme = ThemeService.getThemeMode();
        try {
            for (String theme : List.of("dark", "light")) {
                fx(() -> {
                    ThemeService.switchTheme(theme);
                    var menu = ozon("sortOptionsMenu", MenuButton.class);
                    assertNotNull(menu, "The WB-style sort slot must expose the four existing controls in a native popup");
                    menu.requestFocus();
                    menu.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "", "", javafx.scene.input.KeyCode.DOWN, false, false, false, false));
                    return null;
                });
                pulse();
                fx(() -> {
                    var menu = ozon("sortOptionsMenu", MenuButton.class);
                    assertTrue(menu.isShowing(), "Keyboard Down opens the native sorting menu");
                    var item = assertInstanceOf(CustomMenuItem.class, menu.getItems().getFirst());
                    var content = assertInstanceOf(FlowPane.class, item.getContent());
                    assertFalse(item.isHideOnClick(), "Changing several sort options keeps their popup open");
                    assertEquals(4, content.getChildren().size());
                    for (String id : sortIds()) {
                        var box = ozon(id, CheckBox.class);
                        assertTrue(within(box, content), "The actual existing checkbox is mounted in the popup: " + id);
                        assertTrue(box.isFocusTraversable());
                        assertTrue(box.getScene().getWindow().isShowing());
                        assertNotSame(ozonWindow.getScene(), box.getScene(), "Controls must be rendered in the native popup scene");
                        assertTrue(box.getScene().getRoot().getStyleClass().contains("theme-" + theme), "Popup follows the current app theme");
                    }
                    menu.hide();
                    return null;
                });
            }
        } finally {
            fx(() -> { var menu=ozon("sortOptionsMenu",MenuButton.class); if(menu!=null)menu.hide(); ThemeService.switchTheme(previousTheme.getKey()); return null; });
        }
    }
    @Test void allFourPopupSortControlsKeepDefaultsAndExistingQueryListeners() throws Exception {
        fx(() -> {
            assertNotNull(ozon("sortOptionsMenu",MenuButton.class));
            for (String id : sortIds()) assertTrue(ozon(id,CheckBox.class).isSelected(), id+" keeps its existing default");
            return null;
        });
        var fields = List.of("product", "article", "color", "size");
        for (int index=0;index<sortIds().size();index++) {
            final String id=sortIds().get(index), field=fields.get(index);
            observedQueries.clear();
            fx(() -> { ozon(id,CheckBox.class).fire(); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            assertFalse(observedQueries.isEmpty(), id+" must still call the existing local query listener");
            assertTrue(observedQueries.stream().allMatch(query -> !Arrays.asList(query.sort().split(",")).contains(field)), id+" removal reaches the real query contract");
            observedQueries.clear();
            fx(() -> { ozon(id,CheckBox.class).fire(); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            assertFalse(observedQueries.isEmpty());
            assertTrue(observedQueries.stream().allMatch(query -> query.sort().equals("product,article,color,size")), id+" restoration keeps the existing four-field ordering");
        }
    }
    private static Map<String,Object> geometry(Node node) {
        Objects.requireNonNull(node, "Every compared native node must be rendered");
        var b=bounds(node);var m=new LinkedHashMap<String,Object>();
        m.put("type",node.getClass().getSimpleName());m.put("id",Objects.toString(node.getId(),""));
        m.put("styleClasses",List.copyOf(node.getStyleClass()));m.put("visible",node.isVisible());m.put("managed",node.isManaged());
        m.put("x",b.getMinX());m.put("y",b.getMinY());m.put("width",b.getWidth());m.put("height",b.getHeight());
        if(node instanceof Region region){
            m.put("minWidthProperty",region.getMinWidth());m.put("prefWidthProperty",region.getPrefWidth());
            m.put("computedMinWidth",region.minWidth(region.getHeight()));m.put("computedPrefWidth",region.prefWidth(region.getHeight()));
            m.put("minHeightProperty",region.getMinHeight());m.put("prefHeightProperty",region.getPrefHeight());
            m.put("computedMinHeight",region.minHeight(region.getWidth()));m.put("computedPrefHeight",region.prefHeight(region.getWidth()));
            var p=region.getPadding();m.put("padding",List.of(p.getTop(),p.getRight(),p.getBottom(),p.getLeft()));
            var insets=region.getInsets();m.put("insets",List.of(insets.getTop(),insets.getRight(),insets.getBottom(),insets.getLeft()));
        }
        if(node instanceof Labeled labeled){m.put("text",Objects.toString(labeled.getText(),""));m.put("fontSize",labeled.getFont().getSize());m.put("fontFamily",labeled.getFont().getFamily());m.put("fontStyle",labeled.getFont().getStyle());}
        if(node instanceof HBox box){m.put("spacing",box.getSpacing());m.put("alignment",box.getAlignment().name());}
        if(node instanceof VBox box){m.put("spacing",box.getSpacing());m.put("alignment",box.getAlignment().name());}
        if(node instanceof StackPane pane)m.put("alignment",pane.getAlignment().name());
        return m;
    }
    private static Map<String,Object> detailedGeometry(Parent root,Map<String,Node> nodes) {
        var result=new LinkedHashMap<String,Object>();nodes.forEach((key,value)->result.put(key,geometry(value)));
        result.put("headerChildren",((Parent)nodes.get("header")).getChildrenUnmodifiable().stream().map(OzonWbPackingParityTest::geometry).toList());
        result.put("rootChildren",root.getChildrenUnmodifiable().stream().map(OzonWbPackingParityTest::geometry).toList());
        result.put("tabLabels",root.lookupAll(".tab-label").stream().sorted(Comparator.comparingDouble(node->bounds(node).getMinX())).map(OzonWbPackingParityTest::geometry).toList());
        return result;
    }
    @Test void absoluteNativeWbHeaderTabsFilterAndTableGeometryMatchInBothThemes() throws Exception {
        var previousTheme=ThemeService.getThemeMode();
        var receipt=new LinkedHashMap<String,Object>();
        var checks=new ArrayList<org.junit.jupiter.api.function.Executable>();
        try{
            for(String theme:List.of("dark","light")){
                fx(()->{ThemeService.switchTheme(theme);for(var stage:List.of(wbWindow,ozonWindow)){ThemeService.applyTheme(stage.getScene());stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();}return null;});
                for(boolean busy:List.of(false,true)){
                    fx(()->{
                        var setBusy=OzonDashboardController.class.getDeclaredMethod("setBusy",boolean.class,boolean.class);
                        setBusy.setAccessible(true);setBusy.invoke(dashboard,busy,true);
                        ozonWindow.getScene().getRoot().applyCss();ozonWindow.getScene().getRoot().layout();return null;
                    });
                    pulse();
                    fx(()->{
                    String state=theme+(busy?"-busy":"");
                    Parent wbRoot=wb.getRoot(),ozonRoot=ozon.getRoot();
                    var expected=new LinkedHashMap<String,Node>();var actual=new LinkedHashMap<String,Node>();
                    expected.put("root",wbRoot);actual.put("root",ozonRoot);
                    expected.put("header",((VBox)wbRoot).getChildren().getFirst());actual.put("header",((VBox)ozonRoot).getChildren().getFirst());
                    expected.put("title",wb("titleLabel",Label.class));actual.put("title",ozon("titleLabel",Label.class));
                    expected.put("refresh",wb("refreshButton",Button.class));actual.put("refresh",ozon("refreshButton",Button.class));
                    expected.put("tabs",wb("packingTabPane",TabPane.class));actual.put("tabs",ozon("orderStatusTabs",TabPane.class));
                    expected.put("tabHeader",expected.get("tabs").lookup(".tab-header-area"));actual.put("tabHeader",actual.get("tabs").lookup(".tab-header-area"));
                    expected.put("search",wb("newOrderSearchField",TextField.class));actual.put("search",ozon("postingSearch",TextField.class));
                    expected.put("filterRow",expected.get("search").getParent());actual.put("filterRow",actual.get("search").getParent());
                    expected.put("table",wb("newOrdersTable",TableView.class));actual.put("table",ozon("newOrdersTable",TableView.class));
                    var themeReceipt=new LinkedHashMap<String,Object>();themeReceipt.put("WB",detailedGeometry(wbRoot,expected));themeReceipt.put("Ozon",detailedGeometry(ozonRoot,actual));
                    themeReceipt.put("WBrefreshLoading",geometry(wb("refreshLoading",ProgressIndicator.class)));
                    themeReceipt.put("OzonLoadingIndicator",geometry(ozon("loadingIndicator",ProgressIndicator.class)));
                    themeReceipt.put("OzonLoadingHost",geometry(ozon("loadingIndicator",ProgressIndicator.class).getParent()));
                    themeReceipt.put("busyRequested",busy);receipt.put(state,themeReceipt);
                    boolean spinnerManaged=ozon("loadingIndicator",ProgressIndicator.class).isManaged();
                    boolean spinnerVisible=ozon("loadingIndicator",ProgressIndicator.class).isVisible();
                    checks.add(()->assertTrue(spinnerManaged,state+" native spinner always reserves its intrinsic geometry"));
                    checks.add(()->assertEquals(busy,spinnerVisible,state+" spinner visibility still follows busy state"));
                    for(String key:expected.keySet()){
                        var wbBounds=bounds(expected.get(key));var ozonBounds=bounds(actual.get(key));
                        checks.add(()->assertEquals(wbBounds.getMinY(),ozonBounds.getMinY(),.5,state+" "+key+" absolute Y"));
                        checks.add(()->assertEquals(wbBounds.getHeight(),ozonBounds.getHeight(),.5,state+" "+key+" rendered height"));
                    }
                    return null;
                    });
                }
            }
            String file=System.getProperty("vncode.ui.parity.absolute.metrics.file",profile.resolve("absolute-geometry.json").toString());
            java.nio.file.Files.writeString(Path.of(file),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(receipt));
        }finally{fx(()->{
            var setBusy=OzonDashboardController.class.getDeclaredMethod("setBusy",boolean.class,boolean.class);
            setBusy.setAccessible(true);setBusy.invoke(dashboard,false,true);
            ThemeService.switchTheme(previousTheme.getKey());return null;
        });}
        assertAll("Absolute WB and Ozon composition at the same viewport and theme",checks);
    }

}
