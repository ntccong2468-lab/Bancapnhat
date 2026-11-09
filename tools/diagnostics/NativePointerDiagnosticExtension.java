package com.vncode.app.ui.tnved;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic-only observer. It never moves input, consumes events or changes assertions.
 * Enable output with vncode.native.pointer.diagnostics. Optional vncode.native.pointer.awt.first=graphics
 * initializes backend graphics before the original FX BeforeAll; toolkit/true also
 * initializes Toolkit. These controlled modes are probes, never release acceptance.
 */
public final class NativePointerDiagnosticExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {
    private static final Gson JSON = new GsonBuilder().serializeNulls()
            .registerTypeAdapter(Double.class,(com.google.gson.JsonSerializer<Double>)(value,type,context) ->
                    Double.isFinite(value)?new com.google.gson.JsonPrimitive(value):com.google.gson.JsonNull.INSTANCE)
            .registerTypeAdapter(Float.class,(com.google.gson.JsonSerializer<Float>)(value,type,context) ->
                    Float.isFinite(value)?new com.google.gson.JsonPrimitive(value):com.google.gson.JsonNull.INSTANCE).create();
    private static final Map<Window,Trace> TRACES = new IdentityHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final java.util.concurrent.ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "native-pointer-diagnostic-observer");thread.setDaemon(true);return thread;
            });
    private static boolean installed;
    private static Path output;
    private static ListChangeListener<Window> windowListener;

    private static boolean actualNativeMethod(ExtensionContext context) {
        return context.getRequiredTestClass().getName().equals("com.vncode.app.ui.tnved.FashionTnvedBrowserTest")
                && context.getRequiredTestMethod().getName().equals("actualMouseClickCopiesCodeWithoutApplyingOrClosingThePicker");
    }

    @Override public void beforeAll(ExtensionContext context) throws Exception {
        String directory=System.getProperty("vncode.native.pointer.diagnostics", "");
        String mode=System.getProperty("vncode.native.pointer.awt.first", "false").toLowerCase(java.util.Locale.ROOT);
        if(directory.isBlank() || !(mode.equals("true")||mode.equals("toolkit")||mode.equals("graphics"))
                || !context.getRequiredTestClass().getName().equals("com.vncode.app.ui.tnved.FashionTnvedBrowserTest")) return;
        // Deliberate OPTIONAL diagnostic perturbation. Never call Platform here:
        // this callback precedes the original test's FX-starting @BeforeAll.
        var row=new LinkedHashMap<String,Object>();row.put("time",Instant.now().toString());
        row.put("pid",ProcessHandle.current().pid());row.put("phase","awt-before-original-test-BeforeAll");
        row.put("controlledInitialization",true);row.put("releaseAcceptance",false);
        row.put("helperCalledPlatformHere",false);row.put("awtHeadlessProperty",System.getProperty("java.awt.headless"));
        boolean explicitToolkit=!mode.equals("graphics");
        row.put("mode",mode);row.put("explicitToolkitInitialization",explicitToolkit);
        // Graphics-only models backend image/PDF rendering without adding an
        // explicit Toolkit initialization not present in that source workload.
        var toolkit=explicitToolkit?java.awt.Toolkit.getDefaultToolkit():null;
        var image=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();try{graphics.drawRect(0,0,16,16);}finally{graphics.dispose();}
        row.put("awtToolkitClass",toolkit==null?null:toolkit.getClass().getName());
        var environment=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();row.put("graphicsEnvironmentClass",environment.getClass().getName());
        row.put("headless",java.awt.GraphicsEnvironment.isHeadless());
        if(!java.awt.GraphicsEnvironment.isHeadless()) {
            var device=environment.getDefaultScreenDevice();var configuration=device.getDefaultConfiguration();
            row.put("screenDeviceId",device.getIDstring());row.put("screenBounds",configuration.getBounds().toString());
            row.put("defaultTransform",configuration.getDefaultTransform().toString());
            row.put("normalizingTransform",configuration.getNormalizingTransform().toString());
            if(toolkit!=null)row.put("screenResolution",toolkit.getScreenResolution());
        }
        var path=Path.of(directory);Files.createDirectories(path);append(path.resolve("awt-first.jsonl"),row);
    }

    @Override public void beforeEach(ExtensionContext context) {
        if (!actualNativeMethod(context)) return;
        String directory = System.getProperty("vncode.native.pointer.diagnostics");
        if (directory == null || directory.isBlank()) return;
        try {
            // Do not start JavaFX earlier than the original tests do: that could mask
            // initialization/order bugs. FX test BeforeAll methods start the toolkit.
            Platform.runLater(() -> install(Path.of(directory)));
        } catch (IllegalStateException toolkitNotStarted) {
            // Another BeforeEach callback will try after an FX test starts its toolkit.
        }
    }

    @Override public void afterEach(ExtensionContext context) throws Exception {
        if (!actualNativeMethod(context) || System.getProperty("vncode.native.pointer.diagnostics", "").isBlank()) return;
        var cleanup = new java.util.concurrent.FutureTask<Void>(() -> {
            if (windowListener != null) Window.getWindows().removeListener(windowListener);
            windowListener = null;
            for (var trace : List.copyOf(TRACES.values())) trace.close();
            TRACES.clear();
            if (output != null) append(output.resolve("observer.jsonl"), Map.of("time",Instant.now().toString(),
                    "pid",ProcessHandle.current().pid(),"installed",false,"remainingTraces",TRACES.size(),"observationalOnly",true));
            installed = false;output = null;
            return null;
        });
        Platform.runLater(cleanup);cleanup.get(10,TimeUnit.SECONDS);
        // Drain observational IO only after the original test and its cleanup finish.
        SCHEDULER.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private static void install(Path directory) {
        if (installed) return;
        installed = true;output = directory;
        try {Files.createDirectories(directory);} catch (Exception failure) {throw new IllegalStateException(failure);}
        append(directory.resolve("observer.jsonl"), Map.of("time",Instant.now().toString(),"pid",ProcessHandle.current().pid(),"installed",true,"observationalOnly",true));
        windowListener = change -> {
            while (change.next()) if (change.wasAdded()) for (var window : change.getAddedSubList()) observe(window);
        };
        Window.getWindows().addListener(windowListener);
        for (var window : List.copyOf(Window.getWindows())) observe(window);
    }

    private static void observe(Window window) {
        var scene = window.getScene();
        if (scene == null || !"fashionTnvedBrowser".equals(scene.getRoot().getId()) || TRACES.containsKey(window)) return;
        TRACES.put(window,new Trace(window, scene));
    }

    private static final class Trace {
        final Window window;
        final Scene scene;
        final javafx.scene.Parent root;
        final Path prefix;
        final long begun = System.nanoTime();
        final AtomicInteger sceneMoves = new AtomicInteger(), rootMoves = new AtomicInteger();
        final javafx.event.EventHandler<MouseEvent> sceneEvents;
        final javafx.event.EventHandler<MouseEvent> rootEvents;
        final javafx.event.EventHandler<WindowEvent> shownEvents;
        final javafx.event.EventHandler<WindowEvent> hidingEvents;
        volatile boolean closed;
        ScheduledFuture<?> sampling;

        Trace(Window window, Scene scene) {
            this.window=window;this.scene=scene;this.root=scene.getRoot();
            prefix = output.resolve("pointer-"+ProcessHandle.current().pid()+"-"+IDS.incrementAndGet());
            sceneEvents = event -> event("scene",event);
            rootEvents = event -> event("root",event);
            scene.addEventFilter(MouseEvent.ANY, sceneEvents);
            root.addEventFilter(MouseEvent.ANY, rootEvents);
            shownEvents = event -> {
                // The original test starts its four-second deadline after Stage.show
                // returns, so this native capture occurs before that deadline.
                sample("native-window-shown");capture("shown");
            };
            hidingEvents = event -> {
                // Hiding is in the original finally block, after its assertions.
                sample("before-hide");capture("before-hide");close();
            };
            window.addEventFilter(WindowEvent.WINDOW_SHOWN, shownEvents);
            window.addEventFilter(WindowEvent.WINDOW_HIDING, hidingEvents);
            sample("attached");
            sampling = SCHEDULER.scheduleAtFixedRate(() -> {
                if (!closed) Platform.runLater(() -> {if (!closed) sample("periodic");});
            },250,250,TimeUnit.MILLISECONDS);
        }

        void event(String layer, MouseEvent event) {
            if (event.getEventType()==MouseEvent.MOUSE_MOVED) {
                if (layer.equals("scene")) sceneMoves.incrementAndGet();else rootMoves.incrementAndGet();
            }
            var row = base("mouse");row.put("layer",layer);row.put("type",event.getEventType().getName());
            row.put("screenX",event.getScreenX());row.put("screenY",event.getScreenY());
            row.put("sceneX",event.getSceneX());row.put("sceneY",event.getSceneY());
            row.put("x",event.getX());row.put("y",event.getY());row.put("target",describe(event.getTarget()));
            row.put("consumed",event.isConsumed());row.put("synthesized",event.isSynthesized());
            row.put("primaryDown",event.isPrimaryButtonDown());row.put("middleDown",event.isMiddleButtonDown());row.put("secondaryDown",event.isSecondaryButtonDown());
            append(Path.of(prefix+"-events.jsonl"),row);
        }

        void sample(String reason) {
            var row = base(reason);
            try {
                var root=scene.getRoot();var mouse=new Robot().getMousePosition();
                row.put("mouse",point(mouse));row.put("fxWindow",fxWindow(window));
                row.put("scene",Map.of("x",scene.getX(),"y",scene.getY(),"width",scene.getWidth(),"height",scene.getHeight(),"dispatcher",scene.getEventDispatcher().getClass().getName()));
                row.put("root",node(root,mouse));
                row.put("rootCorner4Screen",point(root.localToScreen(4,4)));
                row.put("rootCenterScreen",point(root.localToScreen(root.getLayoutBounds().getCenterX(),root.getLayoutBounds().getCenterY())));
                row.put("rootContains4",root.contains(4,4));row.put("focusOwner",describe(scene.getFocusOwner()));
                row.put("sceneMoveCount",sceneMoves.get());row.put("rootMoveCount",rootMoves.get());
                var copy=root.lookupAll(".button").stream().filter(n -> n.getId()!=null&&n.getId().startsWith("tnvedCopy_")).findFirst().orElse(null);
                row.put("visibleCopyButton",copy==null?null:node(copy,mouse));
                var windows=new ArrayList<Object>();for(var other:List.copyOf(Window.getWindows())) windows.add(fxWindow(other));row.put("fxWindows",windows);
                var glass=new ArrayList<Object>();
                for(var other:com.sun.glass.ui.Window.getWindowsClone()) glass.add(glassWindow(other));
                row.put("glassWindows",glass);
                var peer=com.sun.javafx.stage.WindowHelper.getPeer(window);
                row.put("targetHwnd",peer==null?null:Long.toUnsignedString(peer.getRawHandle()));
                if(peer instanceof com.sun.javafx.tk.quantum.WindowStage stage) row.put("targetGlassWindow",glassWindow(stage.getPlatformWindow()));
            } catch (Throwable failure) {
                row.put("diagnosticError",failure.getClass().getName()+": "+failure.getMessage());
            }
            append(Path.of(prefix+"-state.jsonl"),row);
        }

        void capture(String phase) {
            var row=base("native-screen-capture-"+phase);
            try {
                // Only this synthetic browser window; never capture unrelated desktop.
                var image=new Robot().getScreenCapture(null,window.getX(),window.getY(),window.getWidth(),window.getHeight());
                int width=(int)image.getWidth(),height=(int)image.getHeight();
                int[] pixels=new int[width*height];
                image.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                var file=Path.of(prefix+"-"+phase+".png");
                SCHEDULER.execute(() -> {
                    try {
                        writePng(file,width,height,pixels);
                        row.put("file",file.getFileName().toString());row.put("width",width);row.put("height",height);
                    }catch(Throwable failure){row.put("captureError",failure.getClass().getName()+": "+failure.getMessage());}
                    writeNow(Path.of(prefix+"-state.jsonl"),row);
                });
            }catch(Throwable failure){row.put("captureError",failure.getClass().getName()+": "+failure.getMessage());append(Path.of(prefix+"-state.jsonl"),row);}
        }

        void close() {
            if (closed) return;
            closed=true;if(sampling!=null)sampling.cancel(false);
            scene.removeEventFilter(MouseEvent.ANY,sceneEvents);root.removeEventFilter(MouseEvent.ANY,rootEvents);
            window.removeEventFilter(WindowEvent.WINDOW_SHOWN,shownEvents);window.removeEventFilter(WindowEvent.WINDOW_HIDING,hidingEvents);
            TRACES.remove(window);
        }
        Map<String,Object> base(String reason) {
            var row=new LinkedHashMap<String,Object>();row.put("time",Instant.now().toString());
            row.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begun));row.put("reason",reason);return row;
        }
    }

    private static Map<String,Object> fxWindow(Window window) {
        var row=new LinkedHashMap<String,Object>();row.put("class",window.getClass().getName());row.put("showing",window.isShowing());row.put("focused",window.isFocused());
        row.put("x",window.getX());row.put("y",window.getY());row.put("width",window.getWidth());row.put("height",window.getHeight());
        row.put("outputScaleX",window.getOutputScaleX());row.put("outputScaleY",window.getOutputScaleY());row.put("renderScaleX",window.getRenderScaleX());row.put("renderScaleY",window.getRenderScaleY());
        row.put("root",window.getScene()==null?null:describe(window.getScene().getRoot()));
        if(window instanceof Stage stage){row.put("modality",stage.getModality().name());row.put("owner",stage.getOwner()==null?null:describe(stage.getOwner()));row.put("alwaysOnTop",stage.isAlwaysOnTop());row.put("iconified",stage.isIconified());row.put("maximized",stage.isMaximized());}
        return row;
    }
    private static Map<String,Object> glassWindow(com.sun.glass.ui.Window window) {
        if(window==null)return null;
        var row=new LinkedHashMap<String,Object>();row.put("hwnd",Long.toUnsignedString(window.getRawHandle()));row.put("nativeHandle",Long.toUnsignedString(window.getNativeHandle()));row.put("nativeWindow",Long.toUnsignedString(window.getNativeWindow()));row.put("ownerHwnd",window.getOwner()==null?null:Long.toUnsignedString(window.getOwner().getRawHandle()));
        row.put("enabled",window.isEnabled());row.put("focused",window.isFocused());row.put("visible",window.isVisible());row.put("closed",window.isClosed());row.put("modal",window.isModal());row.put("popup",window.isPopup());row.put("minimized",window.isMinimized());row.put("level",window.getLevel());row.put("styleMask",window.getStyleMask());
        row.put("platformX",window.getX());row.put("platformY",window.getY());row.put("platformWidth",window.getWidth());row.put("platformHeight",window.getHeight());row.put("platformScaleX",window.getPlatformScaleX());row.put("platformScaleY",window.getPlatformScaleY());row.put("outputScaleX",window.getOutputScaleX());row.put("outputScaleY",window.getOutputScaleY());
        var view=window.getView();row.put("nativeView",view==null?null:Long.toUnsignedString(view.getNativeView()));return row;
    }
    private static Map<String,Object> node(Node node,Point2D mouse) {
        var row=new LinkedHashMap<String,Object>();row.put("description",describe(node));row.put("visible",node.isVisible());row.put("disabled",node.isDisabled());row.put("mouseTransparent",node.isMouseTransparent());row.put("pickOnBounds",node.isPickOnBounds());row.put("opacity",node.getOpacity());row.put("hover",node.isHover());
        row.put("boundsLocal",node.getBoundsInLocal().toString());row.put("boundsScreen",String.valueOf(node.localToScreen(node.getBoundsInLocal())));row.put("transforms",node.getTransforms().toString());
        var local=node.screenToLocal(mouse);row.put("mouseLocal",point(local));row.put("containsMouse",local!=null&&node.contains(local));row.put("clip",node.getClip()==null?null:describe(node.getClip()));return row;
    }
    private static Map<String,Double> point(Point2D point) {return point==null?null:Map.of("x",point.getX(),"y",point.getY());}
    private static String describe(Object value) {
        if(value==null)return null;if(value instanceof Node node)return node.getClass().getName()+"#"+node.getId();return value.getClass().getName();
    }
    /** Pure JDK PNG writer: do not initialize AWT/Java2D or change DPI setup. */
    private static void writePng(Path file,int width,int height,int[] argb) throws java.io.IOException {
        var compressed=new java.io.ByteArrayOutputStream();
        try(var zlib=new java.util.zip.DeflaterOutputStream(compressed)) {
            byte[] scanline=new byte[1+width*4];
            for(int y=0;y<height;y++) {
                scanline[0]=0;
                for(int x=0;x<width;x++) {
                    int pixel=argb[y*width+x],offset=1+x*4;
                    scanline[offset]=(byte)(pixel>>>16);scanline[offset+1]=(byte)(pixel>>>8);
                    scanline[offset+2]=(byte)pixel;scanline[offset+3]=(byte)(pixel>>>24);
                }
                zlib.write(scanline);
            }
        }
        try(var out=new java.io.DataOutputStream(Files.newOutputStream(file))) {
            out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
            var header=new java.io.ByteArrayOutputStream();
            try(var values=new java.io.DataOutputStream(header)) {values.writeInt(width);values.writeInt(height);values.write(new byte[]{8,6,0,0,0});}
            pngChunk(out,"IHDR",header.toByteArray());pngChunk(out,"IDAT",compressed.toByteArray());pngChunk(out,"IEND",new byte[0]);
        }
    }
    private static void pngChunk(java.io.DataOutputStream out,String type,byte[] data) throws java.io.IOException {
        byte[] tag=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(data.length);out.write(tag);out.write(data);
        var crc=new java.util.zip.CRC32();crc.update(tag);crc.update(data);out.writeInt((int)crc.getValue());
    }
    private static void append(Path file,Object row) {
        SCHEDULER.execute(() -> writeNow(file,row));
    }
    private static void writeNow(Path file,Object row) {
        try {Files.writeString(file,JSON.toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception failure){System.err.println("NATIVE_POINTER_DIAGNOSTIC_WRITE_FAILED "+failure.getClass().getSimpleName());}
    }
}
