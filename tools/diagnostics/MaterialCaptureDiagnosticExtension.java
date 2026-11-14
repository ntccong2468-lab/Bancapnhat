package com.vncode.app.testsupport;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.extension.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Observation only: no application state, window focus, controls or assertions are changed. */
public final class MaterialCaptureDiagnosticExtension implements BeforeTestExecutionCallback,AfterTestExecutionCallback {
    private Path output;
    private final List<Watch> watches=new ArrayList<>();
    private ListChangeListener<Window> windows;
    private int serial;

    private static boolean target(ExtensionContext context){return context.getRequiredTestClass().getSimpleName().equals("WorkspaceMaterialRenderingTest")
            &&context.getRequiredTestMethod().getName().equals("focusedShopSelectorKeepsNativeContrastDuringAndAfterMaterialRefresh");}

    @Override public void beforeTestExecution(ExtensionContext context)throws Exception {
        if(!target(context))return;
        String destination=System.getProperty("vncode.material.capture.dir");if(destination==null)return;
        output=Path.of(destination,"focus-observation");Files.createDirectories(output);
        fx(()->{
            windows=change->{while(change.next())if(change.wasAdded())for(Window window:change.getAddedSubList())watch(window);};
            Window.getWindows().addListener(windows);
            for(Window window:List.copyOf(Window.getWindows()))watch(window);return null;
        });
    }
    @Override public void afterTestExecution(ExtensionContext context)throws Exception {
        if(!target(context)||output==null)return;
        fx(()->{Window.getWindows().removeListener(windows);for(Watch watch:watches)watch.close();return null;});
        Files.writeString(output.resolve("method-result.txt"),context.getExecutionException().map(Throwable::toString).orElse("PASS")+"\n");
        watches.clear();output=null;
    }
    private void watch(Window window){
        Scene scene=window.getScene();if(scene==null||!(scene.getRoot().lookup("#shopComboBox") instanceof ComboBox<?> combo))return;
        var watch=new Watch(window,(Region)scene.getRoot(),combo,++serial);watches.add(watch);watch.start();
    }
    private final class Watch {
        private final Window window;private final Region root;private final ComboBox<?> combo;private final int id;
        private Point2D lastOrigin;private int frames;
        private final javafx.event.EventHandler<WindowEvent> hiding=event->record("hiding",true,true);
        private final AnimationTimer timer=new AnimationTimer(){@Override public void handle(long now){
            frames++;boolean png=frames==1||frames==3||frames==5||frames==8;
            if(frames<=8)record("frame-"+frames,png,false);
        }};
        Watch(Window window,Region root,ComboBox<?> combo,int id){this.window=window;this.root=root;this.combo=combo;this.id=id;}
        void start(){window.addEventHandler(WindowEvent.WINDOW_HIDING,hiding);timer.start();}
        void close(){timer.stop();window.removeEventHandler(WindowEvent.WINDOW_HIDING,hiding);}
        void record(String phase,boolean png,boolean sceneAlso){
            String theme=root.getStyleClass().contains("theme-light")?"light":"dark";
            String prefix=String.format(Locale.ROOT,"%02d-%s-%s",id,theme,phase);
            var values=new LinkedHashMap<String,Object>();values.put("phase",phase);values.put("nanoTime",System.nanoTime());
            try {
                Point2D origin=root.localToScreen(0,0);boolean cached=origin==null;
                if(origin!=null)lastOrigin=origin;else origin=lastOrigin;
                values.put("windowShowing",window.isShowing());values.put("windowFocused",window.isFocused());
                values.put("comboFocused",combo.isFocused());values.put("sceneFocusOwnerIsCombo",window.getScene().getFocusOwner()==combo);
                values.put("windowXY",List.of(window.getX(),window.getY()));values.put("rootSize",List.of(root.getWidth(),root.getHeight()));
                values.put("outputScale",List.of(window.getOutputScaleX(),window.getOutputScaleY()));values.put("renderScale",List.of(window.getRenderScaleX(),window.getRenderScaleY()));
                values.put("cachedOrigin",cached);values.put("rootScreenOrigin",String.valueOf(origin));
                values.put("screens",Screen.getScreens().stream().map(screen->Map.of("bounds",screen.getBounds().toString(),"visualBounds",screen.getVisualBounds().toString(),"dpi",screen.getDpi(),"outputScale",List.of(screen.getOutputScaleX(),screen.getOutputScaleY()))).toList());
                var local=root.sceneToLocal(combo.localToScene(combo.getWidth()/2,8));
                Point2D point=combo.localToScreen(combo.getWidth()/2,8);
                if(point==null&&origin!=null)point=origin.add(local);
                values.put("sampleRootPoint",local.toString());values.put("sampleScreenPoint",String.valueOf(point));
                Point2D screenPoint=point;
                values.put("sampleInsideScreen",point!=null&&Screen.getScreens().stream().anyMatch(screen->screen.getBounds().contains(screenPoint)));
                Color ink=(Color)combo.getButtonCell().getTextFill();values.put("ink",ink.toString());
                values.put("comboEffect",String.valueOf(combo.getEffect()));
                values.put("backgroundPatterns",combo.getBackground()==null?0:combo.getBackground().getFills().stream().filter(fill->fill.getFill() instanceof ImagePattern).count());
                var robot=new javafx.scene.robot.Robot();
                if(point!=null)try{Color direct=robot.getPixelColor(point);values.put("robotDirect",direct.toString());values.put("directContrast",contrast(ink,direct));}
                catch(Throwable failure){values.put("robotDirectError",failure.toString());}
                if(png&&origin!=null&&root.getWidth()>0&&root.getHeight()>0)try{
                    var image=robot.getScreenCapture(null,origin.getX(),origin.getY(),root.getWidth(),root.getHeight(),true);
                    values.put("robotImageSize",List.of(image.getWidth(),image.getHeight()));
                    Color pixel=image.getPixelReader().getColor((int)Math.floor(local.getX()),(int)Math.floor(local.getY()));
                    values.put("robotImagePixel",pixel.toString());values.put("robotImageContrast",contrast(ink,pixel));
                    write(image,output.resolve(prefix+"-robot.png"));
                }catch(Throwable failure){values.put("robotCaptureError",failure.toString());}
                if(sceneAlso){
                    var image=window.getScene().snapshot(null);Color pixel=image.getPixelReader().getColor((int)Math.floor(local.getX()),(int)Math.floor(local.getY()));
                    values.put("scenePixel",pixel.toString());values.put("sceneContrast",contrast(ink,pixel));write(image,output.resolve(prefix+"-scene.png"));
                }
            } catch(Throwable failure){values.put("observerError",failure.toString());}
            try{Files.writeString(output.resolve(prefix+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(values)+"\n");}
            catch(Exception ignored){ }
        }
    }
    private static double linear(double c){return c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4);}
    private static double lum(Color c){return .2126*linear(c.getRed())+.7152*linear(c.getGreen())+.0722*linear(c.getBlue());}
    private static double contrast(Color a,Color b){return (Math.max(lum(a),lum(b))+.05)/(Math.min(lum(a),lum(b))+.05);}
    private static void write(Image image,Path file)throws Exception{
        var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        javax.imageio.ImageIO.write(png,"png",file.toFile());
    }
    private static <T>T fx(java.util.concurrent.Callable<T> action)throws Exception{
        var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);
    }
}
