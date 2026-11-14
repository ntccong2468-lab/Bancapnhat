package com.vncode.app.shared;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.geometry.Rectangle2D;
import javafx.beans.value.ChangeListener;
import javafx.stage.Window;
import java.lang.ref.WeakReference;
import java.util.Objects;

/** One interruptible content transition; route state and business lifecycle remain with the host. */
public final class MotionBoundary implements AutoCloseable {
    private static final long MAX_SNAPSHOT_PIXELS=3_000_000;
    private WeakReference<Node> current = new WeakReference<>(null);
    private Departure departure;
    private ViewportClip viewportClip;
    private boolean entryFinished=true;

    /** Call after the host has installed the incoming page in its content slot. */
    public void show(Node incoming, NavigationContext context) {
        Objects.requireNonNull(incoming);
        Objects.requireNonNull(context);
        Node previous = current.get();
        clearDeparture();
        releaseViewportClip();
        if (previous != null) WorkspaceMotion.stop(previous);
        if (previous == incoming) return;
        current = new WeakReference<>(incoming);
        WorkspaceMotion.navigation(incoming, context);
    }

    /** The incoming route is installed synchronously. Only a disposable bitmap exits;
     * no old controller, subscription, focus owner or business completion is retained. */
    public void replace(StackPane host,Node incoming,NavigationContext context){
        Objects.requireNonNull(host);Objects.requireNonNull(incoming);Objects.requireNonNull(context);
        Node previous=current.get();
        if(previous==incoming&&host.getChildren().contains(incoming))return;
        WritableImage outgoing=previous!=null&&host.getChildren().contains(previous)?captureVisible(host):null;
        clearDeparture();
        if(previous!=null)WorkspaceMotion.stop(previous);
        releaseViewportClip();
        host.getChildren().setAll(incoming);
        current=new WeakReference<>(incoming);
        // StackPane does not clip children by default. The wrapper's scale/slide
        // must remain inside the work area and leave the shared chrome untouched.
        boolean contained=WorkspaceMotion.isReduced()||containViewport(host);
        if(!contained)outgoing=null;
        if(outgoing!=null){
            var visual=new ImageView(outgoing);
            visual.getStyleClass().add("workspace-motion-departure");
            visual.setManaged(false);visual.setMouseTransparent(true);visual.setFocusTraversable(false);
            // The live page stays above its departing context: old labels never
            // cover a new action while clicks already belong to the new route.
            visual.setViewOrder(incoming.getViewOrder()+1);
            visual.fitWidthProperty().bind(host.widthProperty());
            visual.fitHeightProperty().bind(host.heightProperty());
            host.getChildren().add(visual);
            var exiting=new Departure(host,visual);departure=exiting;
            exiting.watch();
            if(!exiting.released)WorkspaceMotion.departure(visual,context,exiting::release);
        }
        entryFinished=false;
        if(contained)WorkspaceMotion.navigation(incoming,context,()->{entryFinished=true;releaseViewportIfFinished();});
        else{WorkspaceMotion.selection(incoming);entryFinished=true;}
    }

    private static WritableImage captureVisible(StackPane host){
        if(WorkspaceMotion.isReduced()||host.getChildren().isEmpty()||host.getScene()==null
                ||host.getScene().getWindow()==null||!host.getScene().getWindow().isShowing())return null;
        double width=host.getWidth(),height=host.getHeight();
        if(!Double.isFinite(width)||!Double.isFinite(height)||width<1||height<1)return null;
        int pixelsWide=(int)Math.ceil(width),pixelsHigh=(int)Math.ceil(height);
        // Large/DPI-constrained workspaces keep contextual entry without allocating
        // an unbounded framebuffer or snapshotting off-screen virtualized rows.
        if((long)pixelsWide*pixelsHigh>MAX_SNAPSHOT_PIXELS)return null;
        var parameters=new SnapshotParameters();parameters.setFill(Color.TRANSPARENT);
        var transform=host.getLocalToParentTransform();
        if(!transform.isType2D()||Math.abs(transform.getMxx()-1)>1e-6||Math.abs(transform.getMyy()-1)>1e-6
                ||Math.abs(transform.getMxy())>1e-6||Math.abs(transform.getMyx())>1e-6)return null;
        var origin=host.localToParent(0,0);
        parameters.setViewport(new Rectangle2D(origin.getX(),origin.getY(),width,height));
        try{return host.snapshot(parameters,new WritableImage(pixelsWide,pixelsHigh));}
        catch(IllegalArgumentException|IllegalStateException unavailable){return null;}
    }

    private void clearDeparture(){if(departure!=null)departure.release();}

    private boolean containViewport(StackPane host){
        if(host.getClip()!=null)return true;
        if(host.clipProperty().isBound())return false;
        viewportClip=new ViewportClip(host);return true;
    }
    private void releaseViewportIfFinished(){if(entryFinished&&departure==null)releaseViewportClip();}
    private void releaseViewportClip(){if(viewportClip!=null){viewportClip.release();viewportClip=null;}}
    private final class ViewportClip {
        private final WeakReference<StackPane> host;
        private final Rectangle clip=new Rectangle();
        private Scene scene;
        private Window window;
        private boolean released;
        private final ChangeListener<Scene> sceneChanged=(observable,previous,next)->{
            if(previous!=null&&next==null)MotionBoundary.this.cancel();else watchScene(next);
        };
        private final ChangeListener<Window> windowChanged=(observable,previous,next)->watchWindow(next);
        private final ChangeListener<Boolean> showingChanged=(observable,previous,next)->{if(previous&&!next)MotionBoundary.this.cancel();};
        private ViewportClip(StackPane host){
            this.host=new WeakReference<>(host);clip.widthProperty().bind(host.widthProperty());clip.heightProperty().bind(host.heightProperty());host.setClip(clip);
            host.sceneProperty().addListener(sceneChanged);watchScene(host.getScene());
        }
        private void watchScene(Scene next){
            if(scene!=null)scene.windowProperty().removeListener(windowChanged);scene=next;
            if(scene!=null)scene.windowProperty().addListener(windowChanged);watchWindow(scene==null?null:scene.getWindow());
        }
        private void watchWindow(Window next){
            if(window!=null)window.showingProperty().removeListener(showingChanged);window=next;
            if(window!=null)window.showingProperty().addListener(showingChanged);
        }
        private void release(){
            if(released)return;released=true;
            var owner=host.get();
            if(owner!=null){owner.sceneProperty().removeListener(sceneChanged);if(!owner.clipProperty().isBound()&&owner.getClip()==clip)owner.setClip(null);}
            if(scene!=null)scene.windowProperty().removeListener(windowChanged);
            if(window!=null)window.showingProperty().removeListener(showingChanged);
            scene=null;window=null;
            clip.widthProperty().unbind();clip.heightProperty().unbind();host.clear();
        }
    }

    private final class Departure {
        private final WeakReference<StackPane> host;
        private final ImageView visual;
        private Scene scene;
        private Window window;
        private boolean released;
        private final ChangeListener<javafx.scene.Parent> parentChanged=(observable,previous,next)->{if(next==null)release();};
        private final ChangeListener<Scene> sceneChanged=(observable,previous,next)->{if(next==null)release();};
        private final ChangeListener<Window> windowChanged=(observable,previous,next)->watchWindow(next);
        private final ChangeListener<Boolean> showingChanged=(observable,previous,next)->{if(!next)MotionBoundary.this.cancel();};

        private Departure(StackPane host,ImageView visual){this.host=new WeakReference<>(host);this.visual=visual;}
        private void watch(){
            var owner=host.get();if(owner==null||visual.getParent()!=owner){release();return;}
            owner.sceneProperty().addListener(sceneChanged);
            visual.parentProperty().addListener(parentChanged);
            scene=owner.getScene();
            if(scene!=null){scene.windowProperty().addListener(windowChanged);watchWindow(scene.getWindow());}
        }
        private void watchWindow(Window next){
            if(window!=null)window.showingProperty().removeListener(showingChanged);
            window=next;
            if(window!=null){window.showingProperty().addListener(showingChanged);if(!window.isShowing())release();}
            else release();
        }
        private void release(){
            if(released)return;released=true;
            WorkspaceMotion.stop(visual);
            var owner=host.get();
            if(owner!=null){owner.sceneProperty().removeListener(sceneChanged);owner.getChildren().remove(visual);}
            if(scene!=null)scene.windowProperty().removeListener(windowChanged);
            if(window!=null)window.showingProperty().removeListener(showingChanged);
            scene=null;window=null;host.clear();
            visual.parentProperty().removeListener(parentChanged);
            visual.fitWidthProperty().unbind();visual.fitHeightProperty().unbind();visual.setImage(null);
            if(departure==this)departure=null;
            releaseViewportIfFinished();
        }
    }

    public void cancel() {
        Node node = current.get();
        current.clear();
        clearDeparture();
        if (node != null) WorkspaceMotion.stop(node);
        entryFinished=true;releaseViewportClip();
    }

    @Override public void close() { cancel(); }
}
