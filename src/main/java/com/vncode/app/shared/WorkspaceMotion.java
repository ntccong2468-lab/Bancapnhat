package com.vncode.app.shared;

import javafx.animation.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.transform.Scale;
import javafx.stage.PopupWindow;
import javafx.util.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Bounded, interruptible desktop motion. Visual completion never gates business work. */
public final class WorkspaceMotion {
    private static final String ACTIVE = WorkspaceMotion.class.getName() + ".active";
    private static final String INSTALLED = WorkspaceMotion.class.getName() + ".installed";
    private static final String TAB_BOUNDARY = WorkspaceMotion.class.getName() + ".tabs";
    private static final String DISCLOSURE_BOUNDARY = WorkspaceMotion.class.getName() + ".disclosure";
    private static final String SCROLL_BOUNDARY = WorkspaceMotion.class.getName() + ".scroll";
    private static final String TAB_CONTENT_BOUNDARY = WorkspaceMotion.class.getName() + ".tab-content";
    private static final String LOGICAL_CHILDREN = WorkspaceMotion.class.getName() + ".logical-children";
    private static final Runnable NOTHING = () -> { };
    private static final Set<Node> ACTIVE_NODES = Collections.newSetFromMap(new WeakHashMap<>());

    private WorkspaceMotion() { }

    public static boolean isReduced() { return ThemeService.isReducedMotion(); }
    public static void setReduced(boolean value) { ThemeService.setReducedMotion(value); }

    /** Cancelling restores the exact pre-animation transforms and removes local listeners. */
    public static void stop(Node node) {
        if (node != null && node.getProperties().get(ACTIVE) instanceof ActiveMotion active) active.finish(false);
    }

    /** Commits final state immediately when motion is disabled. FX thread only. */
    public static void cancelAll() {
        for (Node node : List.copyOf(ACTIVE_NODES)) {
            if (node.getProperties().get(ACTIVE) instanceof ActiveMotion active) active.finish(true);
        }
    }

    public static boolean isAnimating(Node node) {
        return node != null && node.getProperties().containsKey(ACTIVE);
    }

    public static void navigation(Node node, NavigationContext context) {
        navigation(node,context,NOTHING);
    }

    /** Visual completion only; callers must install/activate their new content before this call. */
    public static void navigation(Node node, NavigationContext context, Runnable finished) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(finished);
        install(node);
        switch (context) {
            case FORWARD -> enterScaled(node, WorkspaceMotionTokens.HIERARCHY, WorkspaceMotionTokens.FORWARD_X, 0, .9, WorkspaceMotionTokens.FORWARD_SCALE, finished);
            case BACK -> enterScaled(node, WorkspaceMotionTokens.HIERARCHY, WorkspaceMotionTokens.BACK_X, 0, .9, WorkspaceMotionTokens.BACK_SCALE, finished);
            case TAB -> enterScaled(node, WorkspaceMotionTokens.TAB, 0, 0, .92, WorkspaceMotionTokens.TAB_SCALE, finished);
            case OVERLAY -> scaled(node,WorkspaceMotionTokens.MODAL_OPEN,.95,null,finished);
            case SIBLING -> enterScaled(node, WorkspaceMotionTokens.SIBLING, 0, WorkspaceMotionTokens.SIBLING_Y, .92, WorkspaceMotionTokens.SIBLING_SCALE, finished);
        }
    }

    public static void drawer(Node node, boolean opening, Runnable finished) {
        if (opening) enter(node, WorkspaceMotionTokens.DRAWER_OPEN, WorkspaceMotionTokens.DRAWER_X, 0, .78, finished);
        else animate(node, WorkspaceMotionTokens.DRAWER_CLOSE, 0, 0, 1, WorkspaceMotionTokens.DRAWER_X, 0, 0, null, finished);
    }

    /** Local appearance for reports, dialogs and popovers; no window/business handlers are replaced. */
    public static void appearance(Node node, boolean popup, Point2D pivot) {
        scaled(node, popup ? WorkspaceMotionTokens.POPOVER_OPEN : WorkspaceMotionTokens.MODAL_OPEN,
                popup ? .97 : .95, pivot, NOTHING);
    }

    /** Scene roots stay opaque: their material amount and reduced-motion choice
     * must never fade the entire working data or native window. */
    public static void appearanceOpaque(Node node,boolean popup,Point2D pivot){
        var scale=localScale(node,popup?.97:.95,pivot);
        animate(node,popup?WorkspaceMotionTokens.POPOVER_OPEN:WorkspaceMotionTokens.MODAL_OPEN,
                0,0,1,0,0,1,scale,1,NOTHING,false,WorkspaceMotionTokens.WINDOW_SPRING);
    }

    /** Prefer a non-interactive visual copy when the actual window is already closing. */
    public static void disappearance(Node node, boolean popup, Point2D pivot, Runnable finished) {
        var scale=localScale(node,1,pivot);
        animate(node,popup?WorkspaceMotionTokens.POPOVER_CLOSE:WorkspaceMotionTokens.MODAL_CLOSE,
                0,0,1,0,0,0,scale,popup?.97:.975,finished);
    }

    /** Outgoing bitmap only: the real route has already changed and stays interactive. */
    public static void departure(Node visual, NavigationContext context, Runnable finished) {
        double x=switch(context){case FORWARD->-14;case BACK->18;default->0;};
        double targetScale=context==NavigationContext.BACK?1.012:.988;
        var scale=localScale(visual,1,null);
        animate(visual,WorkspaceMotionTokens.PAGE_EXIT,0,0,1,x,0,0,scale,targetScale,finished);
    }

    public static void selection(Node caption) {
        enter(caption, WorkspaceMotionTokens.TAB, 0, 0, .74, NOTHING);
    }

    public static void savedDraft(Node bar) {
        enter(bar, WorkspaceMotionTokens.DRAFT, 0, 8, .78, NOTHING);
    }

    private static void enter(Node node, Duration duration, double x, double y, double opacity, Runnable finished) {
        animate(node, duration, x, y, opacity, 0, 0, 1, null, finished);
    }

    private static void enterScaled(Node node, Duration duration, double x, double y, double opacity,
                                    double fromScale, Runnable finished) {
        var scale=localScale(node,fromScale,null);
        animate(node,duration,x,y,opacity,0,0,1,scale,finished);
    }

    private static void scaled(Node node, Duration duration, double fromScale, Point2D pivot, Runnable finished) {
        var scale = localScale(node,fromScale,pivot);
        animate(node, duration, 0, 0, .8, 0, 0, 1, scale, finished);
    }

    private static Scale localScale(Node node,double amount,Point2D pivot){
        var scale=new Scale(amount,amount);
        if(pivot==null){
            scale.pivotXProperty().bind(node.layoutBoundsProperty().map(bounds->bounds.getCenterX()));
            scale.pivotYProperty().bind(node.layoutBoundsProperty().map(bounds->bounds.getCenterY()));
        }else{scale.setPivotX(pivot.getX());scale.setPivotY(pivot.getY());}
        return scale;
    }

    private static void releaseScale(Scale scale){
        if(scale!=null){scale.pivotXProperty().unbind();scale.pivotYProperty().unbind();}
    }

    private static void animate(Node node, Duration duration, double fromX, double fromY, double fromOpacity,
                                double toX, double toY, double toOpacity, Scale scale, Runnable finished) {
        animate(node,duration,fromX,fromY,fromOpacity,toX,toY,toOpacity,scale,1,finished);
    }

    private static void animate(Node node, Duration duration, double fromX, double fromY, double fromOpacity,
                                double toX, double toY, double toOpacity, Scale scale, double toScale, Runnable finished) {
        animate(node,duration,fromX,fromY,fromOpacity,toX,toY,toOpacity,scale,toScale,finished,true);
    }

    private static void animate(Node node, Duration duration, double fromX, double fromY, double fromOpacity,
                                double toX, double toY, double toOpacity, Scale scale, double toScale, Runnable finished,
                                boolean fadeWhenReduced) {
        animate(node,duration,fromX,fromY,fromOpacity,toX,toY,toOpacity,scale,toScale,finished,fadeWhenReduced,WorkspaceMotionTokens.SPRING);
    }

    private static void animate(Node node, Duration duration, double fromX, double fromY, double fromOpacity,
                                double toX, double toY, double toOpacity, Scale scale, double toScale, Runnable finished,
                                boolean fadeWhenReduced,Interpolator transformCurve) {
        Objects.requireNonNull(node);
        Objects.requireNonNull(finished);
        stop(node);
        // Respect transforms owned by the application and leave bound properties untouched.
        if (node.opacityProperty().isBound() || node.translateXProperty().isBound() || node.translateYProperty().isBound()) {
            releaseScale(scale);
            finished.run();
            return;
        }
        boolean reduced = isReduced();
        if(reduced)releaseScale(scale);
        var active = new ActiveMotion(node, finished, reduced ? null : scale);
        node.getProperties().put(ACTIVE, active);
        ACTIVE_NODES.add(node);
        if (reduced) {
            fromX = fromY = toX = toY = 0;
            fromOpacity = fadeWhenReduced?.85:1;
            // Retain state feedback without a spatial exit or scale.
            toOpacity = 1;
            duration = WorkspaceMotionTokens.REDUCED;
        }
        node.setTranslateX(active.x + fromX);
        node.setTranslateY(active.y + fromY);
        node.setOpacity(active.opacity * fromOpacity);
        if (active.scale != null) node.getTransforms().add(active.scale);
        var frame = new KeyFrame(duration,
                new KeyValue(node.translateXProperty(), active.x + toX, transformCurve),
                new KeyValue(node.translateYProperty(), active.y + toY, transformCurve),
                new KeyValue(node.opacityProperty(), active.opacity * toOpacity, WorkspaceMotionTokens.ARRIVE));
        var timeline = new Timeline(frame);
        if (active.scale != null) timeline.getKeyFrames().add(new KeyFrame(duration,
                new KeyValue(active.scale.xProperty(), toScale, transformCurve),
                new KeyValue(active.scale.yProperty(), toScale, transformCurve)));
        active.animation = timeline;
        timeline.setOnFinished(event -> active.finish(true));
        active.watch();
        timeline.play();
    }

    /** Installs only tab listeners on logical content, skipping virtualized list/table subtrees. */
    public static void install(Node root) {
        if (root == null) return;
        if (root instanceof TabPane tabs) {
            installTabs(tabs);
            for (var tab : tabs.getTabs()) install(tab.getContent());
            return;
        }
        if (root instanceof TableView<?> || root instanceof TreeTableView<?> || root instanceof ListView<?> || root instanceof TreeView<?>) return;
        // Logical children are available before skins/CSS; inspecting skins would miss hidden tabs.
        if (root instanceof ScrollPane scroll) {
            if(scroll.getProperties().putIfAbsent(SCROLL_BOUNDARY,Boolean.TRUE)==null)scroll.contentProperty().addListener((observable,previous,next)->{
                stop(previous);install(next);if(next!=null&&scroll.getScene()!=null)navigation(next,NavigationContext.TAB);
            });
            install(scroll.getContent()); return;
        }
        if (root instanceof TitledPane titled) { installDisclosure(titled); install(titled.getContent()); return; }
        if (root instanceof Accordion accordion) { for (var pane : accordion.getPanes()) install(pane);watchLogicalChildren(root,accordion.getPanes());return; }
        if (root instanceof SplitPane split) { for (Node child : split.getItems()) install(child);watchLogicalChildren(root,split.getItems());return; }
        if (root instanceof ToolBar toolbar) { for (Node child : toolbar.getItems()) install(child);watchLogicalChildren(root,toolbar.getItems());return; }
        if (root instanceof Parent parent&&!(root instanceof Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) install(child);
            watchLogicalChildren(root,parent.getChildrenUnmodifiable());
        }
    }

    private static <T extends Node>void watchLogicalChildren(Node owner,javafx.collections.ObservableList<T> children){
        if(owner.getProperties().putIfAbsent(LOGICAL_CHILDREN,Boolean.TRUE)!=null)return;
        children.addListener((ListChangeListener<T>)change->{
            while(change.next())for(Node child:change.getAddedSubList())install(child);
        });
    }

    /** ThemeService calls this for normal, dialog and popup scenes. All observation is scene-local. */
    public static void install(Scene scene) {
        if (scene == null || scene.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) return;
        installSceneRoot(scene);
        scene.rootProperty().addListener((observable, previous, root) -> {
            stop(previous);
            installSceneRoot(scene);
        });
        scene.windowProperty().addListener((observable, previous, window) -> installSceneRoot(scene));
    }

    public static void install(DialogPane pane) {
        if (pane == null) return;
        install((Node) pane);
        bindWindow(pane, false);
    }

    private static void installSceneRoot(Scene scene) {
        var root = scene.getRoot();
        install((Node) root);
        if (root instanceof DialogPane pane) install(pane);
        else if (scene.getWindow() instanceof PopupWindow) bindWindow(root, true);
    }

    private static void installTabs(TabPane tabs) {
        if (tabs.getProperties().containsKey(TAB_BOUNDARY)) return;
        var boundary = new MotionBoundary();
        tabs.getProperties().put(TAB_BOUNDARY, boundary);
        tabs.getTabs().forEach(WorkspaceMotion::installTabContent);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (previous != null) stop(previous.getContent());
            if (selected != null && selected.getContent() != null) boundary.show(selected.getContent(), NavigationContext.TAB);
        });
        tabs.getTabs().addListener((ListChangeListener<Tab>) changes -> {
            while (changes.next()) {
                for (var tab : changes.getRemoved()) stop(tab.getContent());
                if (changes.wasAdded()) for (var tab : changes.getAddedSubList()) installTabContent(tab);
            }
        });
        tabs.sceneProperty().addListener((observable, previous, scene) -> { if (scene == null) boundary.cancel(); });
    }

    private static void installTabContent(Tab tab){
        if(tab.getProperties().putIfAbsent(TAB_CONTENT_BOUNDARY,Boolean.TRUE)!=null)return;
        install(tab.getContent());
        tab.contentProperty().addListener((observable,previous,next)->{
            stop(previous);install(next);var tabs=tab.getTabPane();
            if(next!=null&&tabs!=null&&tabs.getScene()!=null&&tabs.getSelectionModel().getSelectedItem()==tab)navigation(next,NavigationContext.TAB);
        });
    }

    private static void installDisclosure(TitledPane pane) {
        if (pane.getProperties().putIfAbsent(DISCLOSURE_BOUNDARY,Boolean.TRUE)!=null)return;
        pane.contentProperty().addListener((observable,previous,next)->{
            stop(previous);install(next);
            if(pane.isExpanded()&&next!=null)navigation(next,NavigationContext.TAB);
        });
        pane.expandedProperty().addListener((observable,previous,expanded)->{
            Node content=pane.getContent();
            if(content==null)return;
            if(expanded)navigation(content,NavigationContext.TAB);else stop(content);
        });
    }

    private static void bindWindow(Node node, boolean popup) {
        WorkspaceInteractionMotion.installWindow(node,popup);
    }

    private static final class ActiveMotion {
        private final Node node;
        private final Runnable finished;
        private final Scale scale;
        private final double x, y, opacity;
        private Animation animation;
        private boolean done;
        private final ChangeListener<Scene> detached;
        private final ChangeListener<Parent> removed;

        private ActiveMotion(Node node, Runnable finished, Scale scale) {
            this.node = node; this.finished = finished; this.scale = scale;
            x = node.getTranslateX(); y = node.getTranslateY(); opacity = node.getOpacity();
            detached = (observable, previous, next) -> { if (previous != null && next == null) finish(false); };
            removed = (observable, previous, next) -> { if (previous != null && next == null) finish(false); };
        }

        private void watch() { node.sceneProperty().addListener(detached); node.parentProperty().addListener(removed); }

        private void finish(boolean complete) {
            if (done) return;
            done = true;
            if (animation != null) { animation.setOnFinished(null); animation.stop(); }
            node.sceneProperty().removeListener(detached);
            node.parentProperty().removeListener(removed);
            if (node.getProperties().get(ACTIVE) == this) node.getProperties().remove(ACTIVE);
            ACTIVE_NODES.remove(node);
            if(!node.translateXProperty().isBound())node.setTranslateX(x);
            if(!node.translateYProperty().isBound())node.setTranslateY(y);
            if(!node.opacityProperty().isBound())node.setOpacity(opacity);
            if (scale != null) {node.getTransforms().remove(scale);releaseScale(scale);}
            if (complete) finished.run();
        }
    }
}
