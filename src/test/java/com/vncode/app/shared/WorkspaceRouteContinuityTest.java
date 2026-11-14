package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRouteContinuityTest {
    @TempDir Path profile;
    private String previousData;
    private boolean previousReduced;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    @BeforeEach void initialize()throws Exception{
        previousData=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        previousReduced=fx(()->{ThemeService.initializeAppearance();boolean prior=ThemeService.isReducedMotion();ThemeService.setReducedMotion(false);return prior;});
    }
    @AfterEach void restore()throws Exception{
        fx(()->{WorkspaceMotion.cancelAll();ThemeService.setReducedMotion(previousReduced);return null;});
        if(previousData==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousData);
    }

    @Test void rapidRoutesKeepOneOutgoingVisualAndReleaseItWhenCancelled()throws Exception{
        fx(()->{
            var host=new StackPane();var stage=new Stage();stage.setScene(new Scene(new StackPane(host),500,320));
            var boundary=new MotionBoundary();var first=new StackPane();var second=new StackPane();var third=new StackPane();
            var owned=new Scale(.93,.94);third.getTransforms().add(owned);
            try{
                stage.show();boundary.replace(host,first,NavigationContext.SIBLING);host.applyCss();host.layout();
                boundary.replace(host,second,NavigationContext.FORWARD);
                var oldVisual=host.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                boundary.replace(host,third,NavigationContext.BACK);
                assertNull(oldVisual.getImage());assertNull(oldVisual.getParent());
                assertEquals(1,host.getChildren().stream().filter(ImageView.class::isInstance).count());
                assertTrue(host.getChildren().contains(third));
                assertNotNull(host.getClip(),"Spatial motion must stay within the work area");
                boundary.cancel();assertEquals(1,host.getChildren().size());
                assertNull(host.getClip(),"Temporary clipping must not become an application preference");
                assertEquals(java.util.List.of(owned),third.getTransforms());assertEquals(.93,owned.getX());assertEquals(.94,owned.getY());
                assertEquals(1,third.getOpacity());assertEquals(0,third.getTranslateX());
            }finally{boundary.close();stage.close();}
            return null;
        });
    }

    @Test void reducedMotionInstallsTheSamePageWithoutABitmapOrSpatialZoom()throws Exception{
        fx(()->{
            var host=new StackPane();var stage=new Stage();stage.setScene(new Scene(new StackPane(host),500,320));var boundary=new MotionBoundary();
            try{
                stage.show();boundary.replace(host,new StackPane(),NavigationContext.SIBLING);host.applyCss();host.layout();
                ThemeService.setReducedMotion(true);var incoming=new StackPane(new Button("Ready"));
                boundary.replace(host,incoming,NavigationContext.FORWARD);
                assertEquals(java.util.List.of(incoming),host.getChildren());assertTrue(incoming.getTransforms().isEmpty());
                assertEquals(0,incoming.getTranslateX());assertEquals(0,incoming.getTranslateY());
                WorkspaceMotion.cancelAll();assertEquals(1,incoming.getOpacity());
            }finally{boundary.close();stage.close();}
            return null;
        });
    }

    @Test void aResetDiscardsThePreviousOwnerVisualBeforeAnotherRouteIsMounted()throws Exception{
        fx(()->{
            var host=new StackPane();var stage=new Stage();stage.setScene(new Scene(new StackPane(host),500,320));var boundary=new MotionBoundary();
            try{
                stage.show();boundary.replace(host,new StackPane(),NavigationContext.SIBLING);host.applyCss();host.layout();
                boundary.replace(host,new StackPane(),NavigationContext.FORWARD);
                var visual=host.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                boundary.cancel();assertNull(visual.getImage());
                var nextOwnerPage=new StackPane();boundary.replace(host,nextOwnerPage,NavigationContext.SIBLING);
                assertEquals(java.util.List.of(nextOwnerPage),host.getChildren());
                assertTrue(WorkspaceMotion.isAnimating(nextOwnerPage));
            }finally{boundary.close();stage.close();}
            return null;
        });
    }

    @Test void hidingTheHostWindowReleasesTheDepartureFramebufferImmediately()throws Exception{
        fx(()->{
            var host=new StackPane();var stage=new Stage();stage.setScene(new Scene(new StackPane(host),500,320));var boundary=new MotionBoundary();
            try{
                stage.show();boundary.replace(host,new StackPane(),NavigationContext.SIBLING);host.applyCss();host.layout();
                var incoming=new StackPane();boundary.replace(host,incoming,NavigationContext.FORWARD);
                var visual=host.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                stage.hide();assertNull(visual.getImage());assertNull(visual.getParent());assertEquals(java.util.List.of(incoming),host.getChildren());
            }finally{boundary.close();stage.close();}
            return null;
        });
    }

    @Test void oversizedLogicalContentKeepsEntryFeedbackWithoutAnUnboundedSnapshot()throws Exception{
        fx(()->{
            var host=new StackPane();host.setMinSize(3000,2000);var stage=new Stage();stage.setScene(new Scene(new StackPane(host),500,320));var boundary=new MotionBoundary();
            try{
                stage.show();boundary.replace(host,new StackPane(),NavigationContext.SIBLING);host.applyCss();host.layout();
                assertTrue(host.getWidth()*host.getHeight()>3_000_000);
                var incoming=new StackPane();boundary.replace(host,incoming,NavigationContext.FORWARD);
                assertEquals(java.util.List.of(incoming),host.getChildren());assertTrue(WorkspaceMotion.isAnimating(incoming));
                boundary.cancel();assertTrue(incoming.getTransforms().isEmpty());
            }finally{boundary.close();stage.close();}
            return null;
        });
    }

    @Test void anAlreadyTransformedHostUsesLiveEntryWithoutMisalignedBitmapCoordinates()throws Exception{
        fx(()->{
            var host=new StackPane();var callerScale=new Scale(.9,.9);host.getTransforms().add(callerScale);
            var stage=new Stage();stage.setScene(new Scene(new StackPane(host),500,320));var boundary=new MotionBoundary();
            try{
                stage.show();boundary.replace(host,new StackPane(),NavigationContext.SIBLING);host.applyCss();host.layout();WorkspaceMotion.cancelAll();
                var action=new Button("Ready");var calls=new java.util.concurrent.atomic.AtomicInteger();action.setOnAction(event->calls.incrementAndGet());
                var incoming=new StackPane(action);boundary.replace(host,incoming,NavigationContext.FORWARD);
                assertEquals(java.util.List.of(incoming),host.getChildren());assertTrue(WorkspaceMotion.isAnimating(incoming));
                action.fire();assertEquals(1,calls.get());assertEquals(java.util.List.of(callerScale),host.getTransforms());
                boundary.cancel();assertEquals(.9,callerScale.getX());assertEquals(.9,callerScale.getY());
            }finally{boundary.close();stage.close();}
            return null;
        });
    }

    @Test void disclosureContentsReceiveTheSameScopedMotionAndStopOnCollapse()throws Exception{
        fx(()->{
            var content=new StackPane();var pane=new TitledPane("Synthetic subsection",content);pane.setAnimated(false);pane.setExpanded(false);
            new Scene(new StackPane(pane));WorkspaceMotion.install(pane);
            pane.setExpanded(true);assertTrue(WorkspaceMotion.isAnimating(content));
            pane.setExpanded(false);assertFalse(WorkspaceMotion.isAnimating(content));assertTrue(content.getTransforms().isEmpty());
            return null;
        });
    }

    @Test void anOffsetWorkAreaCapturesItsOwnPixelsWithoutTakingTheHeaderOrCroppingTheBottom()throws Exception{
        fx(()->{
            var host=new StackPane();host.setPrefSize(300,220);host.relocate(70,45);
            var shell=new Pane(host);var stage=new Stage();stage.setScene(new Scene(shell,500,350));var boundary=new MotionBoundary();
            try{
                stage.show();var oldPage=new StackPane();oldPage.setStyle("-fx-background-color:#c02020");
                boundary.replace(host,oldPage,NavigationContext.SIBLING);shell.applyCss();shell.layout();WorkspaceMotion.cancelAll();
                var incoming=new StackPane();incoming.setStyle("-fx-background-color:#2030c0");boundary.replace(host,incoming,NavigationContext.FORWARD);
                var visual=host.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                var image=visual.getImage();assertEquals(0xffc02020,image.getPixelReader().getArgb(1,1));
                assertEquals(0xffc02020,image.getPixelReader().getArgb((int)image.getWidth()-2,(int)image.getHeight()-2));
                var clip=(Rectangle)host.getClip();assertEquals(host.getWidth(),clip.getWidth());assertEquals(host.getHeight(),clip.getHeight());
                assertTrue(visual.getViewOrder()>incoming.getViewOrder(),"The departing picture must stay behind the live page");
            }finally{boundary.close();stage.close();}
            return null;
        });
    }

    @Test void aCallerClipOrBoundClipRemainsOwnedByTheCaller()throws Exception{
        fx(()->{
            var host=new StackPane();var stage=new Stage();stage.setScene(new Scene(new StackPane(host),500,320));var boundary=new MotionBoundary();
            try{
                stage.show();var callerClip=new Rectangle(0,0,400,300);host.setClip(callerClip);
                boundary.replace(host,new StackPane(),NavigationContext.FORWARD);boundary.cancel();assertSame(callerClip,host.getClip());
                var binding=new SimpleObjectProperty<javafx.scene.Node>(null);host.clipProperty().bind(binding);
                var incoming=new StackPane();boundary.replace(host,incoming,NavigationContext.BACK);
                assertTrue(host.clipProperty().isBound());assertNull(host.getClip());
                assertTrue(incoming.getTransforms().isEmpty());assertEquals(0,incoming.getTranslateX());
                boundary.cancel();assertTrue(host.clipProperty().isBound());assertNull(host.getClip());
            }finally{boundary.close();host.clipProperty().unbind();stage.close();}
            return null;
        });
    }

    @Test void lazyNestedSubsectionsAndTabsAreInstalledWithoutInspectingControlSkins()throws Exception{
        fx(()->{
            var root=new StackPane();new Scene(root);WorkspaceMotion.install(root);
            var scroll=new ScrollPane();root.getChildren().add(scroll);
            var tab=new Tab("Lazy");var tabs=new TabPane(tab);scroll.setContent(tabs);
            var content=new StackPane();var disclosure=new TitledPane("Report",content);disclosure.setAnimated(false);disclosure.setExpanded(false);tab.setContent(disclosure);
            disclosure.setExpanded(true);assertTrue(WorkspaceMotion.isAnimating(content));
            disclosure.setExpanded(false);assertFalse(WorkspaceMotion.isAnimating(content));
            WorkspaceMotion.cancelAll();return null;
        });
    }

    @Test void transformResponseSettlesWithABoundedSpringWhileOpacityStaysBounded(){
        double max=0;for(int step=0;step<=1000;step++){
            double fraction=step/1000.0;double value=WorkspaceMotionTokens.SPRING.interpolate(0.0,1.0,fraction);
            assertTrue(Double.isFinite(value)&&value>=0&&value<=1.02);max=Math.max(max,value);
            double opacity=WorkspaceMotionTokens.ARRIVE.interpolate(0.0,1.0,fraction);assertTrue(opacity>=0&&opacity<=1);
            double window=WorkspaceMotionTokens.WINDOW_SPRING.interpolate(0.0,1.0,fraction);assertTrue(window>=0&&window<=1);
        }
        assertTrue(max>1,"Transform response should settle instead of using the old one-way cubic curve");
        assertEquals(0,WorkspaceMotionTokens.SPRING.interpolate(0.0,1.0,0));assertEquals(1,WorkspaceMotionTokens.SPRING.interpolate(0.0,1.0,1));
    }

    @Test void sceneRootAppearanceKeepsWorkingDataOpaqueInNormalAndReducedMotion()throws Exception{
        fx(()->{
            var root=new StackPane(new Button("Report"));root.setOpacity(.9);var callerScale=new Scale(1.1,1.1);root.getTransforms().add(callerScale);
            WorkspaceMotion.appearanceOpaque(root,false,null);
            assertEquals(.9,root.getOpacity());assertTrue(WorkspaceMotion.isAnimating(root));assertEquals(2,root.getTransforms().size());
            WorkspaceMotion.stop(root);assertEquals(java.util.List.of(callerScale),root.getTransforms());
            ThemeService.setReducedMotion(true);WorkspaceMotion.appearanceOpaque(root,false,null);
            assertEquals(.9,root.getOpacity());assertEquals(java.util.List.of(callerScale),root.getTransforms());
            WorkspaceMotion.cancelAll();assertEquals(.9,root.getOpacity());return null;
        });
    }

    @Test void aWindowRootNeverGrowsPastItsViewportDuringActualPulses()throws Exception{
        var finished=new CompletableFuture<Void>();var stage=new Stage[1];var timer=new AnimationTimer[1];
        try{
            fx(()->{
                var root=new StackPane();stage[0]=new Stage();stage[0].setScene(new Scene(root,320,240));stage[0].show();
                WorkspaceMotion.appearanceOpaque(root,false,null);
                assertTrue(root.getLocalToParentTransform().getMxx()<1);
                timer[0]=new AnimationTimer(){
                    @Override public void handle(long now){
                        try{
                            double scale=root.getLocalToParentTransform().getMxx();
                            assertTrue(scale>=.95&&scale<=1,"Whole window roots must stay inside their viewport, including the settle phase");
                            assertEquals(1,root.getOpacity());
                            if(!WorkspaceMotion.isAnimating(root)){stop();assertEquals(1,scale);finished.complete(null);}
                        }catch(Throwable failure){stop();finished.completeExceptionally(failure);}
                    }
                };timer[0].start();return null;
            });
            finished.get(5,TimeUnit.SECONDS);
        }finally{fx(()->{if(timer[0]!=null)timer[0].stop();if(stage[0]!=null)stage[0].close();return null;});}
    }
}
