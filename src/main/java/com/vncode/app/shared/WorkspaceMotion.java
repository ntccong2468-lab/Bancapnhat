package com.vncode.app.shared;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.util.Duration;

/** Small, interruptible UI transitions. No network work or row-by-row animation. */
public final class WorkspaceMotion {
    private static final String ACTIVE=WorkspaceMotion.class.getName()+".active";
    private static final Interpolator OUT=Interpolator.SPLINE(.16,1,.3,1);
    private WorkspaceMotion() { }

    public static boolean isReduced() {
        return Platform.getPreferences().isReducedMotion()
                || Boolean.parseBoolean(ConfigService.getConfigValue("ui_reduced_motion"));
    }
    public static void setReduced(boolean value) {
        ConfigService.setConfigValue("ui_reduced_motion",Boolean.toString(value));
    }
    public static void stop(Node node) {
        if(node.getProperties().remove(ACTIVE) instanceof Animation animation)animation.stop();
        node.setTranslateX(0);node.setTranslateY(0);node.setOpacity(1);
    }
    public static void drawer(Node node,boolean opening,Runnable finished) {
        stop(node);
        if(isReduced()){finished.run();return;}
        node.setTranslateX(opening?28:0);node.setOpacity(opening?.5:1);
        var slide=new TranslateTransition(Duration.millis(opening?220:140),node);
        slide.setToX(opening?0:20);slide.setInterpolator(OUT);
        var fade=new FadeTransition(slide.getDuration(),node);fade.setToValue(opening?1:0);fade.setInterpolator(OUT);
        var animation=new ParallelTransition(slide,fade);node.getProperties().put(ACTIVE,animation);
        animation.setOnFinished(e->{node.getProperties().remove(ACTIVE);node.setTranslateX(0);node.setOpacity(1);finished.run();});
        animation.play();
    }
    public static void selection(Node caption) {
        stop(caption);if(isReduced())return;
        var fade=new FadeTransition(Duration.millis(120),caption);caption.setOpacity(.55);fade.setToValue(1);
        caption.getProperties().put(ACTIVE,fade);fade.setOnFinished(e->caption.getProperties().remove(ACTIVE));fade.play();
    }
    public static void savedDraft(Node bar) {
        stop(bar);if(isReduced())return;
        bar.setTranslateY(8);bar.setOpacity(.6);
        var slide=new TranslateTransition(Duration.millis(180),bar);slide.setToY(0);slide.setInterpolator(OUT);
        var fade=new FadeTransition(slide.getDuration(),bar);fade.setToValue(1);
        var animation=new ParallelTransition(slide,fade);bar.getProperties().put(ACTIVE,animation);
        animation.setOnFinished(e->{bar.getProperties().remove(ACTIVE);bar.setTranslateY(0);bar.setOpacity(1);});animation.play();
    }
}
