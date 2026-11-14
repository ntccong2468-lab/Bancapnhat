package com.vncode.app.ui.workspace;

import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.testsupport.ShopReadTestCleanup;
import com.vncode.app.ui.shop.ShopSidebarController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable native-component visual fixture, never the live app or a seller workflow. */
public final class NativeMotionVideo {
    static Path output;
    static NativeGlassEvidenceTest fixture;
    static Stage stage;
    static StackPane content;
    static FXMLLoader sidebar, header;
    static MotionBoundary boundary=new MotionBoundary();
    static final Map<String,Object> pages=new LinkedHashMap<>();
    static final Map<String,Node> viewports=new LinkedHashMap<>();
    static final CountDownLatch done=new CountDownLatch(1);
    static final List<String> trace=new CopyOnWriteArrayList<>();
    static final Set<Integer> materialImages=new HashSet<>();
    static final ScheduledExecutorService schedule=Executors.newSingleThreadScheduledExecutor();
    static Throwable failure;
    static long start=System.nanoTime();
    static Shop wb,ozon;
    static WorkspaceDetailDrawer drawer;

    static <T>T fx(Callable<T> operation)throws Exception {
        var task=new FutureTask<>(operation);Platform.runLater(task);return task.get(30,TimeUnit.SECONDS);
    }
    static Object field(Object instance,String name)throws Exception {
        var f=instance.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(instance);
    }
    static Object call(Object instance,String method)throws Exception {
        var m=instance.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(instance);
    }
    static void event(String text) {trace.add(String.format(Locale.ROOT,"%.3f %s",(System.nanoTime()-start)/1e9,text));}
    static void step(long millis,String name,Throwing action) {
        schedule.schedule(()->Platform.runLater(()->{
            if(failure!=null)return;
            try{event(name);action.run();}
            catch(Throwable problem){failure=problem;event("FAILED "+problem.getClass().getSimpleName());done.countDown();}
        }),millis,TimeUnit.MILLISECONDS);
    }
    interface Throwing {void run()throws Exception;}

    static void show(String name)throws Exception {
        Object page=pages.get(name);
        if(page==null){var method=NativeGlassEvidenceTest.class.getDeclaredMethod("createPage",String.class);method.setAccessible(true);page=method.invoke(fixture,name);pages.put(name,page);}
        Parent root=(Parent)call(page,"root");
        Node incoming=viewports.computeIfAbsent(name,key->{root.getStyleClass().add("app-page");return new WorkspaceViewport(root);});
        // Fail rather than substitute a handcrafted transition for the new production host API.
        Method hostSwap=Arrays.stream(MotionBoundary.class.getMethods()).filter(m->{var p=m.getParameterTypes();return p.length==3&&p[0].isAssignableFrom(content.getClass())&&p[1].isAssignableFrom(incoming.getClass())&&p[2]==NavigationContext.class;}).findFirst().orElseThrow(()->new IllegalStateException("NEW_PRODUCTION_HOST_TRANSITION_API_NOT_COMPILED"));
        hostSwap.invoke(boundary,content,incoming,NavigationContext.SIBLING);
        var navigation=(ShopSidebarController)sidebar.getController();
        navigation.selectNavigation(name.equals("ozon")?"packingButton":name.equals("settings")?"settingsMenuButton":name.startsWith("products")?"productsButton":"dashboardButton");
        var controls=(WorkspaceHeaderController)header.getController();controls.setShops(List.of(wb,ozon),name.equals("ozon")?ozon:wb);controls.setControls(true,false,true,true);
        event("route="+name+" reduced="+ThemeService.isReducedMotion());
    }
    static Node lookup(String id) {return stage.getScene().getRoot().lookup("#"+id);}
    static void press(Node node)throws Exception {
        if(node==null||node.isDisabled())throw new IllegalStateException("CONTROL_NOT_INTERACTIVE");
        var bounds=node.localToScreen(node.getBoundsInLocal());if(bounds==null)throw new IllegalStateException("CONTROL_NOT_VISIBLE");
        var robot=new javafx.scene.robot.Robot();robot.mouseMove(bounds.getCenterX(),bounds.getCenterY());robot.mousePress(MouseButton.PRIMARY);
        schedule.schedule(()->Platform.runLater(()->new javafx.scene.robot.Robot().mouseRelease(MouseButton.PRIMARY)),110,TimeUnit.MILLISECONDS);
    }
    static void closeSecondary() {
        for(Window window:List.copyOf(Window.getWindows()))if(window!=stage&&window.isShowing()){
            if(window instanceof PopupWindow){window.hide();continue;}
            if(window.getScene()!=null&&window.getScene().getRoot() instanceof DialogPane pane){
                var close=pane.getButtonTypes().stream().filter(t->t.getButtonData().isCancelButton()).findFirst().orElse(null);
                if(close!=null&&pane.lookupButton(close) instanceof Button button){button.fire();continue;}
            }
            window.hide();
        }
    }
    static void traceExitGhost() {
        var queue=new ArrayDeque<Node>();queue.add(stage.getScene().getRoot());
        while(!queue.isEmpty()) {
            var node=queue.removeFirst();
            Object mode=node.getProperties().get("com.vncode.app.shared.WorkspaceInteractionMotion.exit");
            if(mode!=null)event("exit-ghost="+mode);
            if(node instanceof Parent parent)queue.addAll(parent.getChildrenUnmodifiable());
        }
    }
    static void traceMaterial(Parent root) {
        var queue=new ArrayDeque<Node>();queue.add(root);int visited=0,painted=0;
        while(!queue.isEmpty()&&visited++<12000) {
            var node=queue.removeFirst();
            if(node instanceof Region region&&region.getBackground()!=null)for(var fill:region.getBackground().getFills()) {
                if(fill.getFill() instanceof javafx.scene.paint.ImagePattern pattern) {
                    var image=pattern.getImage();materialImages.add(System.identityHashCode(image));
                    if(painted++<60)event("material-node="+node.getClass().getSimpleName()+" id="+Objects.toString(node.getId(),"")+" image="+(int)image.getWidth()+"x"+(int)image.getHeight()+" imageIdentity="+System.identityHashCode(image)+" error="+image.isError()+" sample="+pattern.getX()+","+pattern.getY()+","+pattern.getWidth()+","+pattern.getHeight()+" proportional="+pattern.isProportional()+" lastPaint="+region.getBackground().getFills().getLast().getFill().getClass().getSimpleName()+" tintAlpha="+(region.getBackground().getFills().getLast().getFill() instanceof javafx.scene.paint.Color c?c.getOpacity():-1)+" regionOpacity="+region.getOpacity());
                }
            }
            if(node instanceof Parent parent)queue.addAll(parent.getChildrenUnmodifiable());
        }
        event("material-image-pattern-regions="+painted);
    }
    static int imagePatternCount(Parent root) {
        var queue=new ArrayDeque<Node>();queue.add(root);int count=0,visited=0;
        while(!queue.isEmpty()&&visited++<12000) {
            var node=queue.removeFirst();
            if(node instanceof Region region&&region.getBackground()!=null)
                for(var fill:region.getBackground().getFills())if(fill.getFill() instanceof javafx.scene.paint.ImagePattern)count++;
            if(node instanceof Parent parent)queue.addAll(parent.getChildrenUnmodifiable());
        }
        return count;
    }
    static void traceMainMaterial(){traceMaterial(stage.getScene().getRoot());}
    static void traceVisibleWindows() {
        for(Window window:List.copyOf(Window.getWindows()))if(window.isShowing()&&window!=stage&&window.getScene()!=null) {
            String style=window instanceof Stage child?child.getStyle().toString():window.getClass().getSimpleName();
            event("window-style="+style+" scene-fill="+window.getScene().getFill());traceMaterial(window.getScene().getRoot());event("production-titlebars="+window.getScene().getRoot().lookupAll(".workspace-window-chrome").size());for(Node n:window.getScene().getRoot().lookupAll(".table-view"))if(n instanceof TableView<?> table)event("table-clip="+(table.getClip()==null?"NONE":table.getClip().getClass().getSimpleName())+" rows="+table.getItems().size()+" arc="+(table.getClip() instanceof javafx.scene.shape.Rectangle rect?rect.getArcWidth():0));
        }
    }
    static Dialog<Void> controlProbe;
    static double gestureX,gestureY,gestureWidth;
    static Stage secondaryStage(){return Window.getWindows().stream().filter(w->w!=stage&&w.isShowing()&&w instanceof Stage).map(w->(Stage)w).findFirst().orElseThrow();}
    static void dragReport(){
        Stage child=secondaryStage();Node bar=child.getScene().getRoot().lookup(".workspace-window-chrome");
        if(bar==null)throw new IllegalStateException("MISSING_PRODUCTION_TITLEBAR");
        var b=bar.localToScreen(bar.getBoundsInLocal());gestureX=child.getX();gestureY=child.getY();
        var robot=new javafx.scene.robot.Robot();robot.mouseMove(b.getMinX()+90,b.getCenterY());robot.mousePress(MouseButton.PRIMARY);
        schedule.schedule(()->Platform.runLater(()->{var r=new javafx.scene.robot.Robot();r.mouseMove(b.getMinX()+118,b.getCenterY()+12);r.mouseRelease(MouseButton.PRIMARY);}),120,TimeUnit.MILLISECONDS);
    }
    static void inspectDrag(){Stage child=secondaryStage();event("titlebar-drag-delta="+(child.getX()-gestureX)+","+(child.getY()-gestureY));}
    static void chromeClose()throws Exception {var window=secondaryStage();Node close=window.getScene().getRoot().lookup(".workspace-window-close");if(!(close instanceof Button button))throw new IllegalStateException("MISSING_PRODUCTION_CHROME_CLOSE");button.fire();event("production-title-close-activated");}
    static void createControlProbe(){
        WorkspaceMotion.setReduced(true);controlProbe=new Dialog<>();controlProbe.initOwner(stage);controlProbe.setResizable(true);controlProbe.setTitle("Mẫu control JavaFX · dữ liệu tổng hợp");
        var grid=new GridPane();grid.setHgap(10);grid.setVgap(10);
        var table=new TableView<String>(javafx.collections.FXCollections.observableArrayList("Dòng 1","Dòng 2"));var col=new TableColumn<String,String>("Bảng");col.setCellValueFactory(c->new javafx.beans.property.ReadOnlyStringWrapper(c.getValue()));table.getColumns().add(col);
        var tree=new TreeView<String>(new TreeItem<>("Cây"));tree.getRoot().getChildren().add(new TreeItem<>("Mục con"));tree.getRoot().setExpanded(true);
        var treeTable=new TreeTableView<String>(new TreeItem<>("Cây bảng"));treeTable.getRoot().getChildren().add(new TreeItem<>("Mục con"));treeTable.getRoot().setExpanded(true);var tc=new TreeTableColumn<String,String>("Cột");tc.setCellValueFactory(c->new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().getValue()));treeTable.getColumns().add(tc);
        var combo=new ComboBox<String>(javafx.collections.FXCollections.observableArrayList("Giá trị 1","Giá trị 2"));combo.setValue("Giá trị 1");var choice=new ChoiceBox<String>(javafx.collections.FXCollections.observableArrayList("Lựa chọn"));choice.setValue("Lựa chọn");
        var textArea=new TextArea("Văn bản tổng hợp");textArea.setPrefRowCount(2);textArea.setWrapText(true);
        var surface=new VBox(new Label("Panel có nền vật liệu"));surface.getStyleClass().add("surface");
        List<Region> nodes=List.of(new Button("Nút"),new MenuButton("Menu",null,new MenuItem("Mục")),new TextField("Trường văn bản"),new PasswordField(),textArea,combo,choice,new DatePicker(java.time.LocalDate.of(2026,11,14)),new Spinner<Integer>(0,100,5),new ListView<String>(javafx.collections.FXCollections.observableArrayList("Danh sách","Mục 2")),tree,table,treeTable,new TitledPane("Thu gọn",new Label("Nội dung")),new ToolBar(new Button("Công cụ")),surface);
        for(int i=0;i<nodes.size();i++){Region node=nodes.get(i);node.setId("nativeProbe"+i);node.setMinSize(0,0);node.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);node.setPrefHeight(76);var card=new VBox(4,new Label(node.getClass().getSimpleName()),node);card.setMinSize(0,0);VBox.setVgrow(node,Priority.ALWAYS);GridPane.setHgrow(card,Priority.ALWAYS);GridPane.setVgrow(card,Priority.ALWAYS);grid.add(card,i%4,i/4);}
        controlProbe.getDialogPane().setContent(grid);controlProbe.getDialogPane().setPrefSize(1060,650);controlProbe.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);ThemeService.applyThemeDialog(controlProbe);controlProbe.show();
    }
    static void resizeProbe(){var pane=controlProbe.getDialogPane();Stage child=(Stage)pane.getScene().getWindow();gestureWidth=child.getWidth();var point=pane.localToScreen(pane.getWidth()-15,pane.getHeight()/2);var robot=new javafx.scene.robot.Robot();robot.mouseMove(point);schedule.schedule(()->Platform.runLater(()->new javafx.scene.robot.Robot().mousePress(MouseButton.PRIMARY)),250,TimeUnit.MILLISECONDS);schedule.schedule(()->Platform.runLater(()->new javafx.scene.robot.Robot().mouseMove(point.getX()+54,point.getY())),420,TimeUnit.MILLISECONDS);schedule.schedule(()->Platform.runLater(()->new javafx.scene.robot.Robot().mouseRelease(MouseButton.PRIMARY)),600,TimeUnit.MILLISECONDS);}
    static void inspectResize(){Stage child=(Stage)controlProbe.getDialogPane().getScene().getWindow();event("resizable-probe-width-delta="+(child.getWidth()-gestureWidth));traceVisibleWindows();}
    static void seedFullGs1Report(){var reports=new SyncRunRepository();String run=reports.begin(wb,"gs1","SOURCE");for(int i=0;i<24;i++)reports.add(wb,run,i%2==0?"CATALOG":"ZNACK","FULL-"+i,"","FULL-"+i,"Blue","XL","","SUCCEEDED","","{}");event("seeded-synthetic-full-gs1=24");}
    static void settingsMaterial()throws Exception{
        Parent root=(Parent)call(pages.get("settings"),"root");var pane=(Region)root;
        boolean opaque=pane.getBackground()!=null&&pane.getBackground().getFills().stream().anyMatch(f->!(f.getFill() instanceof javafx.scene.paint.Color c)||c.getOpacity()>0);
        event("settings-outer-opaque="+opaque);event("settings-section-count="+root.lookupAll(".appearance-section").size());traceMaterial(root);
    }
    static Object wallpaper(){var bg=((Region)stage.getScene().getRoot()).getBackground();return bg==null?null:bg.getFills().stream().filter(f->f.getFill() instanceof javafx.scene.paint.ImagePattern).map(f->((javafx.scene.paint.ImagePattern)f.getFill()).getImage()).findFirst().orElse(null);}
    static final List<Map<String,Object>> pageMaterialObservations=new ArrayList<>(),mountMaterialObservations=new ArrayList<>();
    static boolean visiblePaint(javafx.scene.paint.Paint paint){
        if(paint instanceof javafx.scene.paint.Color c)return c.getOpacity()>0;
        if(paint instanceof javafx.scene.paint.LinearGradient g)return g.getStops().stream().anyMatch(stop->stop.getColor().getOpacity()>0);
        if(paint instanceof javafx.scene.paint.RadialGradient g)return g.getStops().stream().anyMatch(stop->stop.getColor().getOpacity()>0);
        return true;
    }
    static List<Node> logicalChildren(Node node){
        if(node instanceof ScrollPane c)return c.getContent()==null?List.of():List.of(c.getContent());
        if(node instanceof TitledPane c)return c.getContent()==null?List.of():List.of(c.getContent());
        if(node instanceof Accordion c)return new ArrayList<>(c.getPanes());
        if(node instanceof SplitPane c)return new ArrayList<>(c.getItems());
        if(node instanceof ToolBar c)return new ArrayList<>(c.getItems());
        if(node instanceof ButtonBar c)return new ArrayList<>(c.getButtons());
        if(node instanceof TabPane c){var tab=c.getSelectionModel().getSelectedItem();return tab==null||tab.getContent()==null?List.of():List.of(tab.getContent());}
        if(node instanceof DialogPane c)return new ArrayList<>(c.getChildren());
        if(node instanceof Control)return List.of();
        return node instanceof Parent parent?new ArrayList<>(parent.getChildrenUnmodifiable()):List.of();
    }
    static boolean effectiveVisible(Node node){for(Node current=node;current!=null;current=current.getParent())if(!current.isVisible())return false;return true;}
    static boolean within(Node node,Node root){for(Node current=node;current!=null;current=current.getParent())if(current==root)return true;return false;}
    static Map<String,Object> pageMaterialState(String route,String theme)throws Exception{
        Parent root=stage.getScene().getRoot(),page=(Parent)call(pages.get(route),"root");
        Method role=WorkspaceBackdropMaterial.class.getDeclaredMethod("role",Region.class,Scene.class);role.setAccessible(true);
        var queue=new ArrayDeque<Node>();queue.add(root);var observations=new ArrayList<Map<String,Object>>();var missing=new ArrayList<String>();int pageTargets=0;
        while(!queue.isEmpty()){
            Node node=queue.removeFirst();queue.addAll(logicalChildren(node));
            if(!(node instanceof Region region)||(int)role.invoke(null,region,stage.getScene())==0||!effectiveVisible(node)||region.getWidth()<1||region.getHeight()<1)continue;
            var bg=region.getBackground();if(bg==null||bg.getFills().isEmpty()||!bg.getImages().isEmpty()||bg.getFills().stream().noneMatch(fill->visiblePaint(fill.getFill())))continue;
            var origin=((javafx.css.StyleableProperty<?>)region.backgroundProperty()).getStyleOrigin();
            String key=node.getClass().getSimpleName()+"#"+Objects.toString(node.getId(),"");var item=new LinkedHashMap<String,Object>();
            item.put("node",key);item.put("classes",List.copyOf(node.getStyleClass()));item.put("styleOrigin",Objects.toString(origin,"null"));item.put("pageSurface",within(node,page));item.put("regionOpacity",region.getOpacity());
            var patterns=bg.getFills().stream().filter(fill->fill.getFill() instanceof javafx.scene.paint.ImagePattern).map(fill->(javafx.scene.paint.ImagePattern)fill.getFill()).toList();
            item.put("imagePatternCount",patterns.size());item.put("tintAlpha",bg.getFills().getLast().getFill() instanceof javafx.scene.paint.Color c?c.getOpacity():null);
            boolean callerHistory=Boolean.TRUE.equals(region.getProperties().get(WorkspaceBackdropMaterial.class.getName()+".callerBackground"));
            boolean caller=callerHistory||region.backgroundProperty().isBound()||origin==javafx.css.StyleOrigin.USER||origin==javafx.css.StyleOrigin.INLINE;
            item.put("callerBackgroundHistoryOptOut",callerHistory);item.put("callerOwnedException",caller);
            if(!patterns.isEmpty()){var pattern=patterns.getFirst();item.put("imageIdentity",System.identityHashCode(pattern.getImage()));item.put("imageWidth",pattern.getImage().getWidth());item.put("imageHeight",pattern.getImage().getHeight());item.put("imageError",pattern.getImage().isError());item.put("anchor",List.of(pattern.getX(),pattern.getY(),pattern.getWidth(),pattern.getHeight()));}
            observations.add(item);
            if(!caller){if(within(node,page))pageTargets++;if(patterns.isEmpty()||patterns.stream().anyMatch(p->p.getImage().isError()||p.getImage().getWidth()!=960||p.getImage().getHeight()!=600))missing.add(key);}
        }
        var rootBackground=((Region)root).getBackground();var rootPattern=rootBackground==null?null:rootBackground.getFills().stream().map(f->f.getFill()).filter(p->p instanceof javafx.scene.paint.ImagePattern).map(p->(javafx.scene.paint.ImagePattern)p).findFirst().orElse(null);
        String sourcePixel="NONE",nativePixel="NONE";double maxPixelDelta=255;
        if(rootPattern!=null&&rootPattern.getWidth()>0&&rootPattern.getHeight()>0){
            double localX=2,localY=2,u=(localX-rootPattern.getX())/rootPattern.getWidth(),v=(localY-rootPattern.getY())/rootPattern.getHeight();
            u-=Math.floor(u);v-=Math.floor(v);var image=rootPattern.getImage();
            var expected=image.getPixelReader().getColor(Math.min((int)image.getWidth()-1,(int)(u*image.getWidth())),Math.min((int)image.getHeight()-1,(int)(v*image.getHeight())));
            var screen=root.localToScreen(localX,localY);var actual=new javafx.scene.robot.Robot().getPixelColor(screen.getX(),screen.getY());
            maxPixelDelta=255*Math.max(Math.abs(expected.getRed()-actual.getRed()),Math.max(Math.abs(expected.getGreen()-actual.getGreen()),Math.abs(expected.getBlue()-actual.getBlue())));sourcePixel=expected.toString();nativePixel=actual.toString();
        }
        var result=new LinkedHashMap<String,Object>();result.put("nativeWallpaperSampleLocal",List.of(2,2));result.put("sourceWallpaperPixel",sourcePixel);result.put("actualNativeWallpaperPixel",nativePixel);result.put("nativeWallpaperMaxChannelDelta",maxPixelDelta);result.put("nativeWallpaperRendered",maxPixelDelta<=3);result.put("rootScale",List.of(root.getScaleX(),root.getScaleY()));result.put("route",route);result.put("theme",theme);result.put("glassEnabled",ThemeService.isGlassEnabled());result.put("glassTransparency",ThemeService.getGlassTransparency());result.put("rootImagePattern",wallpaper()!=null);result.put("workingPageTargetCount",pageTargets);result.put("missingTargets",missing);result.put("surfaces",observations);
        result.put("ready",ThemeService.isGlassEnabled()&&wallpaper()!=null&&pageTargets>0&&missing.isEmpty()&&maxPixelDelta<=3);return result;
    }
    static Map<String,Object> materialReadyFrames(String route,String theme)throws Exception{
        var done=new CompletableFuture<Map<String,Object>>();
        fx(()->{var toolkit=com.sun.javafx.tk.Toolkit.getToolkit();
            // A no-op heartbeat keeps real toolkit pulses flowing while an idle scene settles.
            // Material/pixel checks still run strictly AFTER scene CSS/layout/synchronization.
            var heartbeat=new javafx.animation.AnimationTimer(){public void handle(long now){}};
            var listener=new com.sun.javafx.tk.TKPulseListener(){String previous;int steady,frames;public void pulse(){try{
                frames++;var current=pageMaterialState(route,theme);String fingerprint=current.toString();
                if(Boolean.TRUE.equals(current.get("ready"))&&fingerprint.equals(previous))steady++;else steady=0;previous=fingerprint;
                if(steady>=5){toolkit.removePostSceneTkPulseListener(this);heartbeat.stop();current.put("stableRenderedFrames",steady);current.put("barrierPhase","POST_SCENE_CSS_LAYOUT_SYNC_AND_NATIVE_PIXEL");current.put("observedPulseCount",frames);done.complete(current);}
                else if(frames>150){toolkit.removePostSceneTkPulseListener(this);heartbeat.stop();event("MATERIAL_PAGE_NOT_READY "+current);done.completeExceptionally(new IllegalStateException("MATERIAL_PAGE_NOT_READY:"+route+":"+theme));}
                else Platform.requestNextPulse();
            }catch(Exception e){toolkit.removePostSceneTkPulseListener(this);heartbeat.stop();done.completeExceptionally(e);}}};
            toolkit.addPostSceneTkPulseListener(listener);heartbeat.start();Platform.requestNextPulse();return null;
        });return done.get(20,TimeUnit.SECONDS);
    }
    static void sweep()throws Exception{
        System.setProperty("vncode.workbench.capture.dir",output.resolve("pages").toString());
        var routesField=NativeGlassEvidenceTest.class.getDeclaredField("ROUTES");routesField.setAccessible(true);
        @SuppressWarnings("unchecked") var allRoutes=(List<String>)routesField.get(null);
        String routeFilter=System.getProperty("capture.routes","");var routes=routeFilter.isBlank()?allRoutes:allRoutes.stream().filter(route->List.of(routeFilter.split(",")).contains(route)).toList();
        var navMethod=NativeGlassEvidenceTest.class.getDeclaredMethod("navigationId",String.class);navMethod.setAccessible(true);
        fx(()->{WorkspaceMotion.setReduced(true);return null;});
        for(String route:routes){
            fx(()->{show(route);((ShopSidebarController)sidebar.getController()).selectNavigation((String)navMethod.invoke(null,route));((WorkspaceHeaderController)header.getController()).setShops(List.of(wb,ozon),route.contains("ozon")?ozon:wb);return null;});ShopReadTestCleanup.awaitSharedIdle();
            fx(()->{var root=stage.getScene().getRoot();root.applyCss();root.layout();return null;});
            var mount=materialReadyFrames(route,ThemeService.getCurrentTheme().name().toLowerCase(Locale.ROOT));mount.put("themeChangedForAcceptance",false);mountMaterialObservations.add(mount);
            Files.writeString(output.resolve("mount-material-observations.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(mountMaterialObservations));
            event("dynamic-mount-ready="+route+" targets="+mount.get("workingPageTargetCount")+" themeChanged=false");
            for(String theme:List.of("dark","light")){
                Object previous=fx(NativeMotionVideo::wallpaper);String old=ThemeService.getCurrentTheme().name().toLowerCase(Locale.ROOT);
                fx(()->{ThemeService.switchTheme(theme);return null;});long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                while(System.nanoTime()<until){Object current=fx(NativeMotionVideo::wallpaper);if(current!=null&&(old.equals(theme)||current!=previous))break;Thread.sleep(40);}
                fx(()->{var root=stage.getScene().getRoot();root.applyCss();root.layout();WorkspaceMotion.cancelAll();return null;});
                var material=materialReadyFrames(route,theme);pageMaterialObservations.add(material);
                fx(()->{var root=stage.getScene().getRoot();var atCapture=pageMaterialState(route,theme);if(!Boolean.TRUE.equals(atCapture.get("ready")))throw new IllegalStateException("MATERIAL_LOST_BEFORE_CAPTURE");com.vncode.app.testsupport.UiEvidence.captureWindow(stage,"page-"+route+"-"+theme);event("page-captured="+route+" theme="+theme+" patterns="+imagePatternCount(root)+" pageTargets="+atCapture.get("workingPageTargetCount")+" missing="+atCapture.get("missingTargets")+" frameBarrierAfterAllTargets=5");return null;});
                Files.writeString(output.resolve("page-material-observations.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(pageMaterialObservations));
            }
            fx(()->{content.getChildren().clear();Object page=pages.remove(route);viewports.remove(route);if(page!=null)((Runnable)call(page,"close")).run();return null;});ShopReadTestCleanup.awaitSharedIdle();
        }
        event("sweep-pages="+routes.size()*2);event("licenseCalls="+((java.util.concurrent.atomic.AtomicInteger)field(fixture,"licenseCalls")).get());
        fx(()->{drawer.clear();boundary.close();stage.hide();return null;});schedule.shutdownNow();fixture.cleanup();Files.write(output.resolve("trace.txt"),trace);Platform.exit();System.exit(0);
    }

    static void report()throws Exception {
        var type=Class.forName("com.vncode.app.ui.gs1.Gs1SyncReportDialog");var method=type.getDeclaredMethod("show",Window.class,Shop.class);method.setAccessible(true);method.invoke(null,stage,wb);
    }
    static void registrationReport()throws Exception {
        // The disposable profile has no registration-attempt run. This opens the
        // actual report/search controls through their local empty-history branch.
        var type=Class.forName("com.vncode.app.ui.znackregistration.RegistrationSyncReportDialog");
        var method=type.getDeclaredMethod("show",Window.class,Shop.class,String.class);method.setAccessible(true);method.invoke(null,stage,wb,"");
    }
    static void setup()throws Exception {
        NativeGlassEvidenceTest.toolkit();fixture=new NativeGlassEvidenceTest();fixture.profile=Files.createTempDirectory(output,"synthetic-profile-");fixture.seed();
        wb=(Shop)field(fixture,"wb");ozon=(Shop)field(fixture,"ozon");
        var reports=new SyncRunRepository();String run=reports.begin(wb,"gs1","SOURCE");
        reports.add(wb,run,"CATALOG","DEMO-1","","DEMO-1","Blue","XL","","SUCCEEDED","","{}");
        reports.add(wb,run,"ZNACK","DEMO-2","","DEMO-2","Brown","2XL","","RUNNING","","{}");
        fx(()->{
            I18nService.getInstance().setLanguage(AppLanguage.VI);WorkspaceMotion.setReduced(false);ThemeService.switchTheme("dark");
            sidebar=FxmlViewLoader.loader(ShopSidebarController.class,"shop-sidebar-view.fxml");header=FxmlViewLoader.loader(WorkspaceHeaderController.class,"workspace-header-view.fxml");
            Parent nav=sidebar.load(),top=header.load();content=new StackPane();content.setMinSize(0,0);drawer=new WorkspaceDetailDrawer(content);
            Button report=new Button("Báo cáo GS1 · dữ liệu tổng hợp");report.setId("motionReportTrigger");report.getStyleClass().add("btn-secondary");report.setOnAction(e->{try{report();}catch(Exception problem){failure=problem;done.countDown();}});
            Label notice=new Label("NATIVE JAVAFX · DỮ LIỆU TỔNG HỢP · Không gửi API / không kích hoạt license");notice.getStyleClass().add("text-muted");
            var tools=new HBox(12,report,notice);tools.setPadding(new Insets(5,0,0,0));
            var center=new BorderPane(content,top,null,tools,null);BorderPane.setMargin(top,new Insets(0,0,8,0));
            var root=new BorderPane(center,null,null,null,nav);root.getStyleClass().add("workspace-shell");root.setPadding(new Insets(8));BorderPane.setMargin(nav,new Insets(0,10,0,0));
            stage=new Stage(StageStyle.UNDECORATED);stage.setTitle("Native component motion evidence — synthetic");stage.setScene(new Scene(root,1440,900));ThemeService.applyTheme(stage.getScene());stage.setX(0);stage.setY(0);stage.show();
            ((Button)sidebar.getNamespace().get("productsButton")).setOnAction(e->{try{show("products-wb");}catch(Exception problem){failure=problem;done.countDown();}});
            ((Button)sidebar.getNamespace().get("packingButton")).setOnAction(e->{try{show("ozon");}catch(Exception problem){failure=problem;done.countDown();}});
            show("welcome");root.applyCss();root.layout();return null;
        });
        // The production generated wallpaper is asynchronous; wait for actual
        // shell+chrome ImagePatterns instead of assuming a fixed FX pulse count.
        long materialLimit=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(fx(()->imagePatternCount(stage.getScene().getRoot()))<3&&System.nanoTime()<materialLimit)Thread.sleep(40);
        int readyPatterns=fx(()->imagePatternCount(stage.getScene().getRoot()));
        if(readyPatterns<3)throw new IllegalStateException("PRODUCTION_MATERIAL_NOT_READY:"+readyPatterns);
        event("ready-material-image-patterns="+readyPatterns);
        Files.writeString(output.resolve("ready"),"ready");
    }
    public static void main(String[] args)throws Exception {
        output=Path.of(args[0]);Files.createDirectories(output);
        Thread.setDefaultUncaughtExceptionHandler((thread,problem)->{event("FAILED_UNCAUGHT "+thread.getName()+" "+problem.getClass().getSimpleName());try{Files.write(output.resolve("trace.txt"),trace);}catch(Exception ignored){}problem.printStackTrace();schedule.shutdownNow();Platform.exit();System.exit(1);});setup();
        if(args.length>1&&args[1].equals("--sweep")){sweep();return;}
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!Files.exists(output.resolve("go"))&&System.nanoTime()<limit)Thread.sleep(20);
        if(!Files.exists(output.resolve("go")))throw new IllegalStateException("VIDEO_RECORDER_NOT_READY");start=System.nanoTime();
        step(700,"press-products",()->press((Node)sidebar.getNamespace().get("productsButton")));
        step(2200,"press-product-checkbox",()->{var pane=(Parent)call(pages.get("products-wb"),"root");var check=pane.lookupAll(".list-cell .check-box").stream().filter(n->n instanceof CheckBox&&!n.isDisabled()).findFirst().orElseThrow();press(check);});
        step(2800,"inspect-dark-workspace-material",NativeMotionVideo::traceMainMaterial);
        step(3400,"press-ozon",()->press((Node)sidebar.getNamespace().get("packingButton")));
        step(5000,"switch-ozon-tab",()->((TabPane)lookup("orderStatusTabs")).getSelectionModel().select(1));
        step(6400,"open-manage-popover",()->((WorkspaceHeaderController)header.getController()).showShopManagement());
        step(7600,"close-manage-popover",NativeMotionVideo::closeSecondary);
        step(8200,"open-real-gs1-report",()->press(lookup("motionReportTrigger")));
        step(9200,"inspect-dark-gs1-material",NativeMotionVideo::traceVisibleWindows);
        step(9500,"drag-production-titlebar",NativeMotionVideo::dragReport);
        step(9900,"inspect-titlebar-drag",NativeMotionVideo::inspectDrag);
        step(10300,"close-gs1-report",NativeMotionVideo::closeSecondary);
        step(11300,"open-real-registration-report-with-search",NativeMotionVideo::registrationReport);
        step(12100,"inspect-registration-scene-backing",NativeMotionVideo::traceVisibleWindows);
        step(13100,"close-registration-via-production-titlebar",()->{chromeClose();traceExitGhost();});
        step(14200,"open-real-error-report",()->com.vncode.app.ui.report.ErrorReportDialog.show("Cửa hàng tổng hợp","DEMO","Kiểm tra hiệu ứng báo cáo","DEMO_VISUAL_ONLY · Không gửi thay đổi lên sàn"));
        step(15100,"inspect-dark-error-material",NativeMotionVideo::traceVisibleWindows);
        step(16100,"close-error-report",()->{closeSecondary();traceExitGhost();});
        step(17200,"switch-light-and-products",()->{ThemeService.switchTheme("light");show("products-wb");});
        step(19000,"inspect-light-workspace-material",NativeMotionVideo::traceMainMaterial);
        step(20200,"open-real-context-drawer",()->drawer.show("Chi tiết · dữ liệu tổng hợp",List.of(new WorkspaceDetailDrawer.Section("Biến thể",List.of(new WorkspaceDetailDrawer.Field("Article","DEMO-1"),new WorkspaceDetailDrawer.Field("Size","2XL / 52"))))));
        step(22100,"close-context-drawer",()->press(lookup("closeWorkspaceDetail")));
        step(22800,"seed-synthetic-full-gs1-report",NativeMotionVideo::seedFullGs1Report);
        step(23200,"open-light-real-gs1-report",NativeMotionVideo::report);
        step(24100,"inspect-light-gs1-material",NativeMotionVideo::traceVisibleWindows);
        step(25400,"close-light-gs1-report",NativeMotionVideo::closeSecondary);
        step(26400,"open-light-real-registration-report",NativeMotionVideo::registrationReport);
        step(27300,"inspect-light-registration-material",NativeMotionVideo::traceVisibleWindows);
        step(28500,"close-light-registration-report",()->{closeSecondary();traceExitGhost();});
        step(29600,"open-light-real-error-report",()->com.vncode.app.ui.report.ErrorReportDialog.show("Cửa hàng tổng hợp","DEMO","Kiểm tra chất liệu nền sáng","DEMO_VISUAL_ONLY · Không gửi thay đổi lên sàn"));
        step(30500,"inspect-light-error-material",NativeMotionVideo::traceVisibleWindows);
        step(31600,"close-light-error-report",()->{closeSecondary();traceExitGhost();});
        step(32400,"open-native-control-probe16",NativeMotionVideo::createControlProbe);
        step(33400,"inspect-control-probe16",NativeMotionVideo::traceVisibleWindows);
        step(33600,"resize-native-control-probe",NativeMotionVideo::resizeProbe);
        step(34700,"inspect-probe-resize",NativeMotionVideo::inspectResize);
        step(35000,"switch-control-probe-dark",()->ThemeService.switchTheme("dark"));
        step(36300,"inspect-dark-control-probe16",NativeMotionVideo::traceVisibleWindows);
        step(37000,"close-native-control-probe",NativeMotionVideo::closeSecondary);
        step(37800,"show-real-appearance",()->{WorkspaceMotion.setReduced(false);ThemeService.switchTheme("light");show("settings");});
        step(39100,"inspect-settings-no-outer-frame",NativeMotionVideo::settingsMaterial);
        step(40000,"enable-reduced-motion",()->press(lookup("appearanceReducedMotion")));
        step(41100,"navigate-reduced",()->show("ozon"));
        step(42800,"finish",()->{closeSecondary();event("material-shared-images-observed="+materialImages.size());event("licenseCalls="+((java.util.concurrent.atomic.AtomicInteger)field(fixture,"licenseCalls")).get());Files.writeString(output.resolve("done"),"done");done.countDown();});
        if(!done.await(55,TimeUnit.SECONDS))failure=new IllegalStateException("VIDEO_CHOREOGRAPHY_TIMEOUT");
        fx(()->{closeSecondary();drawer.clear();boundary.close();for(Object page:pages.values())((Runnable)call(page,"close")).run();stage.hide();return null;});
        schedule.shutdownNow();ShopReadTestCleanup.awaitSharedIdle();fixture.cleanup();
        Files.write(output.resolve("trace.txt"),trace);Platform.exit();
        if(failure!=null){failure.printStackTrace();System.exit(1);}System.exit(0);
    }
}
