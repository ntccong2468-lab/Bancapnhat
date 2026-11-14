package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Control;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.image.WritableImage;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Rendered material, not a screenshot of an HTML substitute or a live seller profile. */
class WorkspaceMaterialRenderingTest {
    @TempDir Path profile;
    private String previous;

    @BeforeAll static void toolkit() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
        fx(() -> { Platform.setImplicitExit(false);return null; });
    }
    @BeforeEach void profile() {
        previous=System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
    }
    @AfterEach void cleanup() throws Exception {
        fx(() -> {
            for(var window:List.copyOf(Window.getWindows()))if(window instanceof Stage stage)stage.close();
            ThemeService.setReducedMotion(false);return null;
        });
        if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);
    }

    /** The 2026-11-14 all-surface brief supersedes the archived b00 opaque-workspace assertions. */
    @Test void floatingChromeAndWorkingGlassKeepTextSolidAndLegible() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);ThemeService.setGlassEnabled(true);ThemeService.setGlassTransparency(55);
            var materialChecks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            for(var theme:ThemeService.Theme.values()) {
                ThemeService.setCurrentTheme(theme);
                var title=new Label("VN Code · Synthetic material evidence");
                var chrome=new HBox(title);chrome.getStyleClass().add("workspace-glass-header");
                var data=new TableView<String>();data.getColumns().add(new TableColumn<>("Synthetic data"));data.setPrefHeight(110);
                var caption=new Label("Frosted working surface · solid readable text");
                var content=new VBox(8,caption,data);content.getStyleClass().add("workspace-sheet");
                var root=new VBox(16,chrome,content);root.setPadding(new Insets(24));
                var stage=new Stage();stage.setScene(new Scene(root,620,300));ThemeService.applyTheme(stage.getScene());stage.show();
                try {
                    root.applyCss();root.layout();
                    var pixels=root.snapshot(null,null);int center=(int)(chrome.getLayoutX()+chrome.getWidth()/2);
                    var top=pixels.getPixelReader().getColor(center,(int)Math.floor(chrome.getLayoutY()));
                    var bottom=pixels.getPixelReader().getColor(center,(int)Math.ceil(chrome.getLayoutY()+chrome.getHeight())-1);
                    capture(root,theme);
                    materialChecks.add(() -> assertTrue(brightness(top)-brightness(bottom)>.035,
                            "Floating material needs directional edge light rather than a flat translucent rectangle: "+theme+" top="+top+" bottom="+bottom));
                    assertTint(content,.68,.81,"Working panel uses a readable frosted tint");
                    assertTint(data,.68,.81,"The table owns one shared material, not a fill on each row");
                    assertNull(data.getEffect(),"The existing floating sheet supplies elevation outside the rounded table clip");
                    assertTrue(data.lookupAll(".table-row-cell").stream().allMatch(node -> node.getEffect()==null),
                            "Virtualized rows must not acquire per-row blur/shadows");
                    Color background=pixel(root,pixels,content,content.getWidth()-24,24);
                    assertTrue(contrast((Color)caption.getTextFill(),background)>=4.5,"Body text contrasts with the actual rendered panel: "+theme);
                    assertEquals(1,title.getOpacity());assertEquals(1,root.getOpacity());assertEquals(1,stage.getOpacity());
                } finally { stage.close(); }
            }
            assertAll(materialChecks);
            return null;
        });
    }

    @Test void dialogContentDoesNotPaintASquareWrapperAroundRoundedReportFields() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);ThemeService.setGlassEnabled(true);ThemeService.setGlassTransparency(55);
            var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            for(var theme:ThemeService.Theme.values())for(boolean rootDialog:new boolean[]{true,false}) {
                ThemeService.setCurrentTheme(theme);
                var report=new TextArea("Synthetic report\nNo seller API or profile is used.");report.setPrefHeight(160);
                var content=new VBox(8,new Label("Synthetic report details"),report);
                var dialog=new DialogPane();dialog.getStyleClass().add("app-dialog-pane");
                dialog.setHeaderText("Report");dialog.setContent(content);dialog.getButtonTypes().add(ButtonType.CLOSE);
                Parent root=rootDialog?dialog:new VBox(dialog);
                var stage=new Stage();stage.setScene(new Scene(root,620,350));ThemeService.applyTheme(stage.getScene());stage.show();
                try {
                    root.applyCss();root.layout();report.requestFocus();root.applyCss();
                    capture(root,"dialog-wrapper-"+theme.getKey()+"-"+(rootDialog?"root":"descendant")+".png");
                    String context=theme+" "+(rootDialog?"root":"descendant");
                    boolean wrapperTransparent=transparent(content),wrapperBorderless=borderless(content);
                    checks.add(() -> assertTrue(wrapperTransparent,"The report content wrapper must reveal its rounded dialog frame: "+context));
                    checks.add(() -> assertTrue(wrapperBorderless,"The report wrapper must not add a second square border: "+context));
                    assertEquals(StageStyle.DECORATED,stage.getStyle(),"Native window caption is unchanged");
                    assertTint(report,.73,.86,"Report field keeps its own readable frosted tint");
                    assertEquals(1,ink(report).getOpacity(),1e-6,"Report text remains solid");
                    assertTrue(report.getBackground().getFills().getLast().getRadii().getTopLeftHorizontalRadius()>0,
                            "The report field retains its rounded surface");
                    assertSame(report,stage.getScene().getFocusOwner(),"Keyboard focus remains on the report field");
                    assertTrue(report.getBorder().getStrokes().stream().anyMatch(stroke -> stroke.getWidths().getTop()>0
                                    && stroke.getTopStroke() instanceof Color color && color.getOpacity()>0),
                            "Focused report field retains a visible border");
                } finally {stage.close();}
            }
            assertAll(checks);return null;
        });
    }

    @Test void nativeTableHeadersRespectStandaloneCornersWithoutRoundingEmbeddedHeaders() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);ThemeService.setGlassEnabled(true);ThemeService.setGlassTransparency(55);
            var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            Color ground=Color.rgb(101,37,89);
            for(var theme:ThemeService.Theme.values())for(boolean tree:new boolean[]{false,true}) {
                ThemeService.setCurrentTheme(theme);
                Control standalone=headerTable(tree),embedded=headerTable(tree);
                var sheet=new VBox(embedded);sheet.getStyleClass().add("workspace-sheet");
                var root=new HBox(18,standalone,sheet);root.setPadding(new Insets(24));
                root.setStyle("-fx-background-color: rgb(101,37,89);");
                var stage=new Stage();stage.setScene(new Scene(root,626,248));ThemeService.applyTheme(stage.getScene());stage.show();
                try {
                    root.applyCss();root.layout();
                    WritableImage image=root.snapshot(null,null);
                    capture(root,"table-header-corners-"+theme.getKey()+"-"+(tree?"tree":"table")+".png");
                    String context=theme+" "+(tree?"TreeTableView":"TableView");
                    Color left=pixel(root,image,standalone,2,2),right=pixel(root,image,standalone,standalone.getWidth()-3,2);
                    Color embeddedCorner=pixel(root,image,embedded,2,2),embeddedCenter=pixel(root,image,embedded,embedded.getWidth()/2,2);
                    // Small offset shadows are now intentional; a square opaque header is not.
                    checks.add(() -> assertTrue(distance(ground,left)<.045,"Standalone header must not overpaint the top-left rounded corner: "+context));
                    checks.add(() -> assertTrue(distance(ground,right)<.045,"Standalone header must not overpaint the top-right rounded corner: "+context));
                    checks.add(() -> assertTrue(distance(embeddedCenter,embeddedCorner)<.045,"Embedded header retains its straight edge inside the owning sheet: "+context));
                    assertNotEquals(ground,embeddedCorner,"Embedded work data retains a legible material tint");
                    assertEquals(0,((Region)embedded.lookup(".column-header-background")).getBackground().getFills().getLast().getRadii().getTopLeftHorizontalRadius(),
                            "The owning sheet supplies the embedded header's outer corners");
                    var header=(Region)standalone.lookup(".column-header-background");
                    Color headerFill=pixel(root,image,header,header.getWidth()/2,header.getHeight()-4);
                    Color bottomRule=pixel(root,image,header,header.getWidth()/2,header.getHeight()-1);
                    assertNotEquals(headerFill,bottomRule,"The header's bottom divider remains visible");
                    standalone.requestFocus();root.applyCss();
                    assertSame(standalone,stage.getScene().getFocusOwner(),"Table keyboard focus remains available");
                    assertTrue(standalone.lookupAll(".table-row-cell,.tree-table-row-cell").stream().allMatch(node -> node.getEffect()==null),
                            "Only the whole control floats; rows stay free of effects");
                } finally {stage.close();}
            }
            assertAll(checks);return null;
        });
    }

    @Test void settingsHasNoOpaqueEnclosureAndFloatingWidgetsKeepNativeContrast() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);ThemeService.setGlassEnabled(true);ThemeService.setGlassTransparency(55);
            var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            for(var theme:ThemeService.Theme.values()) {
                ThemeService.setCurrentTheme(theme);
                var settings=new com.vncode.app.ui.workspace.WorkspaceAppearancePane();
                var field=new TextField("Synthetic product");field.setPrefWidth(240);
                var action=new Button("Apply");action.setPrefWidth(120);
                var primary=new Button("Save");primary.getStyleClass().add("btn-primary");primary.setPrefWidth(120);
                var widgets=new HBox(12,field,action,primary);
                var root=new VBox(12,settings,widgets);root.setPadding(new Insets(24));
                var stage=new Stage();stage.setScene(new Scene(root,820,790));ThemeService.applyTheme(stage.getScene());stage.show();
                try {
                    root.applyCss();root.layout();
                    capture(root,"all-surface-settings-"+theme.getKey()+".png");
                    boolean openPage=transparent(settings);
                    checks.add(() -> assertTrue(openPage,"Settings must not retain the superseded opaque enclosing card: "+theme));
                    for(var node:settings.lookupAll(".appearance-section")) {
                        assertTint((Region)node,.68,.81,"Settings section floats independently");
                        var section=(VBox)node;var first=section.getChildren().getFirst();
                        double inset=first.localToScene(0,0).getX()-section.localToScene(0,0).getX();
                        assertTrue(inset>=14,"The rounded section must give its text horizontal breathing room");
                    }
                    assertTint(field,.73,.86,"Field tint preserves readable frost");
                    assertTint(action,.65,.77,"Ordinary action uses the shared floating control tint");
                    WritableImage pixels=root.snapshot(null,null);
                    for(var control:List.of(field,action,primary)) {
                        Color foreground=ink(control);
                        Color background=pixel(root,pixels,control,control.getWidth()-18,8);
                        assertEquals(1,foreground.getOpacity(),"Control text is never faded with material");
                        assertTrue(contrast(foreground,background)>=4.5,"Text must contrast with actual native background: "+theme+" "+control.getClass().getSimpleName());
                    }
                    field.requestFocus();root.applyCss();assertSame(field,stage.getScene().getFocusOwner());
                    assertTrue(field.getBorder().getStrokes().stream().anyMatch(stroke -> stroke.getTopStroke() instanceof Color color&&color.getOpacity()>0),
                            "The field retains visible keyboard focus");
                } finally {settings.dispose();stage.close();}
                checkSettingsAt940(theme);
            }
            assertAll(checks);return null;
        });
    }

    @Test void focusedShopSelectorKeepsNativeContrastDuringAndAfterMaterialRefresh() throws Exception {
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var theme:ThemeService.Theme.values()) {
            var state=fx(() -> {
                ThemeService.setReducedMotion(true);ThemeService.setGlassEnabled(true);ThemeService.setGlassTransparency(55);ThemeService.setCurrentTheme(theme);
                var loader=FxmlViewLoader.loader(com.vncode.app.ui.workspace.WorkspaceHeaderController.class,"workspace-header-view.fxml");
                Parent chrome=loader.load();var controller=(com.vncode.app.ui.workspace.WorkspaceHeaderController)loader.getController();
                var shop=new com.vncode.app.models.Shop(1,"Synthetic store",com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,"","fixture");
                controller.setShops(List.of(shop),shop);
                var root=new VBox(chrome);root.setPadding(new Insets(24));root.getStyleClass().add("workspace-shell");
                var stage=new Stage(StageStyle.UNDECORATED);stage.setScene(new Scene(root,1040,180));ThemeService.applyTheme(stage.getScene());stage.show();
                var combo=(javafx.scene.control.ComboBox<?>)root.lookup("#shopComboBox");combo.requestFocus();root.applyCss();root.layout();
                return new FocusSelector(stage,root,combo,controller);
            });
            try {
                awaitSelectorMaterial(state.combo());
                fx(() -> {
                    assertSame(state.combo(),state.stage().getScene().getFocusOwner());
                    // A real material change invalidates the cached CSS background before
                    // the shared backdrop is reapplied. The focused field must stay readable.
                    ThemeService.previewGlassTransparency(56);state.root().applyCss();state.root().layout();
                    var image=state.stage().getScene().snapshot(null);
                    Color background=pixel(state.root(),image,state.combo(),state.combo().getWidth()/2,8);
                    Color foreground=(Color)state.combo().getButtonCell().getTextFill();double ratio=contrast(foreground,background);
                    checks.add(() -> assertTrue(ratio>=4.5,"Focused store text during material refresh: "+theme+" ink="+foreground+" background="+background+" contrast="+ratio));
                    capture(state.root(),"focus-selector-refresh-"+theme.getKey()+".png");return null;
                });
                awaitSelectorMaterial(state.combo());
                fx(() -> {
                    pulse();
                    var origin=state.root().localToScreen(0,0);
                    var image=new javafx.scene.robot.Robot().getScreenCapture(null,origin.getX(),origin.getY(),state.root().getWidth(),state.root().getHeight());
                    Color background=pixel(state.root(),image,state.combo(),state.combo().getWidth()/2,8);
                    Color foreground=(Color)state.combo().getButtonCell().getTextFill();double ratio=contrast(foreground,background);
                    checks.add(() -> assertTrue(ratio>=4.5,"Focused store text on the actual shown frame: "+theme+" contrast="+ratio));
                    var focus=(Color)state.combo().getBorder().getStrokes().getFirst().getTopStroke();
                    assertTrue(focus.getOpacity()>.9&&contrast(focus,background)>=3,"The focused field retains a visible boundary");
                    assertEquals(1,foreground.getOpacity());assertEquals(1,state.combo().getOpacity());
                    assertEquals("Synthetic store",((com.vncode.app.models.Shop)state.combo().getValue()).getName());
                    assertSame(state.combo(),state.stage().getScene().getFocusOwner());return null;
                });
            } finally {fx(() -> {state.controller().dispose();state.stage().close();return null;});}
        }
        assertAll(checks);
    }
    private record FocusSelector(Stage stage,VBox root,javafx.scene.control.ComboBox<?> combo,
                                 com.vncode.app.ui.workspace.WorkspaceHeaderController controller) { }
    private static void awaitSelectorMaterial(Region control) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);int stable=0;
        while(System.nanoTime()<until&&stable<3) {
            boolean ready=fx(() -> control.getBackground()!=null&&control.getBackground().getFills().stream().anyMatch(fill -> fill.getFill() instanceof javafx.scene.paint.ImagePattern));
            stable=ready?stable+1:0;Thread.sleep(20);
        }
        assertEquals(3,stable,"The focused field must receive its real shared backdrop before screen checks");
    }

    private static void checkSettingsAt940(ThemeService.Theme theme) throws Exception {
        var sidebar=FxmlViewLoader.loader(com.vncode.app.ui.shop.ShopSidebarController.class,"shop-sidebar-view.fxml");
        var header=FxmlViewLoader.loader(com.vncode.app.ui.workspace.WorkspaceHeaderController.class,"workspace-header-view.fxml");
        Parent navigation=sidebar.load(),toolbar=header.load();
        var nav=(com.vncode.app.ui.shop.ShopSidebarController)sidebar.getController();
        var top=(com.vncode.app.ui.workspace.WorkspaceHeaderController)header.getController();
        var settings=new com.vncode.app.ui.workspace.WorkspaceAppearancePane();
        settings.setOnTranslationSettings(()->{});settings.setOnCheckUpdate(()->{});settings.setOnPrinterSettings(()->{});
        settings.setOnAccount(()->{});settings.setOnAbout(()->{});
        var viewport=new com.vncode.app.ui.workspace.WorkspaceViewport(settings);
        var work=new javafx.scene.layout.BorderPane(viewport,toolbar,null,null,null);
        var root=new javafx.scene.layout.BorderPane(work,null,null,null,navigation);root.setPadding(new Insets(8));
        javafx.scene.layout.BorderPane.setMargin(navigation,new Insets(0,10,0,0));
        var stage=new Stage();stage.setScene(new Scene(root,940,720));ThemeService.applyTheme(stage.getScene());
        try {
            stage.show();nav.setViewportWidth(1440); // Exercise the user's expanded-sidebar override at a narrow desktop.
            nav.selectNavigation("settingsMenuButton");root.applyCss();root.layout();root.applyCss();root.layout();
            pulse();root.applyCss();root.layout();
            assertEquals(940,stage.getScene().getWidth(),1);
            var bounds=settings.localToScene(settings.getLayoutBounds());int count=0;
            for(var node:settings.lookupAll("*"))if(node instanceof javafx.scene.control.ButtonBase||node instanceof javafx.scene.control.ComboBoxBase<?>
                    ||node instanceof javafx.scene.control.ChoiceBox<?>||node instanceof javafx.scene.control.Slider
                    ||node instanceof javafx.scene.control.CheckBox) {
                if(!node.isVisible()||!node.isManaged())continue;
                var control=node.localToScene(node.getLayoutBounds());count++;
                assertTrue(control.getMinX()>=bounds.getMinX()-1&&control.getMaxX()<=bounds.getMaxX()+1,
                        "Settings control must fit the 940px workspace horizontally: "+theme+" "+node.getId()+" "+control);
            }
            assertTrue(count>=12,"All native settings actions and appearance controls must be exercised");
            capture(root,"settings-940-"+theme.getKey()+".png");
            var scroll=(javafx.scene.control.ScrollPane)settings.lookup(".appearance-scroll");scroll.setVvalue(1);viewport.setVvalue(1);root.layout();
            pulse();root.applyCss();root.layout();
            var visible=(Region)scroll.lookup(".viewport");var visibleBounds=visible.localToScene(visible.getLayoutBounds());
            for(String id:List.of("appearanceTranslationSettings","appearanceCheckUpdate","appearanceAccount","appearancePrinterSettings","appearanceAbout")) {
                var node=settings.lookup("#"+id);var action=node.localToScene(node.getLayoutBounds());
                assertTrue(action.getMinY()>=visibleBounds.getMinY()-1&&action.getMaxY()<=visibleBounds.getMaxY()+1,
                        "The full Settings action remains reachable after scrolling at940px: "+theme+" "+id);
            }
            capture(root,"settings-940-"+theme.getKey()+"-bottom.png");
        } finally {settings.dispose();top.dispose();nav.dispose();stage.close();}
    }
    private static void pulse(){
        Object key=new Object();var timer=new javafx.animation.AnimationTimer(){int frames;
            @Override public void handle(long now){if(++frames==3){stop();Platform.exitNestedEventLoop(key,null);}}
        };timer.start();Platform.enterNestedEventLoop(key);
    }

    private static Control headerTable(boolean tree){
        Control control;
        if(tree){var table=new TreeTableView<String>();table.getColumns().add(new TreeTableColumn<>("Synthetic header"));control=table;}
        else {var table=new TableView<String>();table.getColumns().add(new TableColumn<>("Synthetic header"));control=table;}
        control.setMinSize(260,160);control.setPrefSize(260,160);control.setMaxSize(260,160);return control;
    }
    private static Color pixel(Parent root,WritableImage image,javafx.scene.Node node,double x,double y){
        var point=root.sceneToLocal(node.localToScene(x,y));return image.getPixelReader().getColor((int)Math.floor(point.getX()),(int)Math.floor(point.getY()));
    }

    private static void capture(VBox root,ThemeService.Theme theme) throws Exception {
        capture(root,"material-"+theme.getKey()+".png");
    }
    private static void capture(Parent root,String file) throws Exception {
        String output=System.getProperty("vncode.material.capture.dir");if(output==null)return;
        Path directory=Path.of(output);Files.createDirectories(directory);WritableImage image=root.snapshot(null,null);
        var pixels=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        javax.imageio.ImageIO.write(pixels,"png",directory.resolve(file).toFile());
    }
    private static boolean transparent(Region region){return region.getBackground()==null||region.getBackground().getFills().stream()
            .allMatch(fill -> fill.getFill() instanceof Color color&&color.getOpacity()==0);}
    private static boolean borderless(Region region){return region.getBorder()==null||region.getBorder().getStrokes().stream()
            .allMatch(stroke -> stroke.getWidths().getTop()==0&&stroke.getWidths().getRight()==0
                    &&stroke.getWidths().getBottom()==0&&stroke.getWidths().getLeft()==0
                    ||stroke.getTopStroke() instanceof Color color&&color.getOpacity()==0);}
    private static void assertTint(Region region,double minimum,double maximum,String reason){
        assertNotNull(region.getBackground(),reason+": material is missing");
        assertFalse(region.getBackground().getFills().isEmpty(),reason+": material fill is missing");
        double alpha=((Color)region.getBackground().getFills().getLast().getFill()).getOpacity();
        assertTrue(alpha>=minimum&&alpha<=maximum,reason+": alpha="+alpha);
    }
    private static Color ink(Control control){
        if(control instanceof javafx.scene.control.Labeled labeled)return (Color)labeled.getTextFill();
        return control.lookupAll(".text").stream().filter(node -> node instanceof javafx.scene.text.Text text&&!text.getText().isBlank())
                .map(node -> (Color)((javafx.scene.text.Text)node).getFill()).findFirst().orElseThrow();
    }
    private static double distance(Color a,Color b){return Math.max(Math.abs(a.getRed()-b.getRed()),Math.max(Math.abs(a.getGreen()-b.getGreen()),Math.abs(a.getBlue()-b.getBlue())));}
    private static double contrast(Color a,Color b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private static double luminance(Color c){return .2126*linear(c.getRed())+.7152*linear(c.getGreen())+.0722*linear(c.getBlue());}
    private static double linear(double c){return c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4);}
    private static double brightness(Color color){return (color.getRed()+color.getGreen()+color.getBlue())/3;}
    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(25,TimeUnit.SECONDS);
    }
}
