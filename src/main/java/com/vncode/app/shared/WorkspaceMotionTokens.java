package com.vncode.app.shared;

import javafx.animation.Interpolator;
import javafx.util.Duration;

/** Native counterparts of the approved desktop motion tokens. */
public final class WorkspaceMotionTokens {
    public static final Duration SIBLING = Duration.millis(240);
    public static final Duration HIERARCHY = Duration.millis(300);
    public static final Duration TAB = Duration.millis(180);
    public static final Duration PAGE_EXIT = Duration.millis(170);
    public static final Duration DRAWER_OPEN = Duration.millis(220);
    public static final Duration DRAWER_CLOSE = Duration.millis(130);
    public static final Duration MODAL_OPEN = Duration.millis(240);
    public static final Duration MODAL_CLOSE = Duration.millis(150);
    public static final Duration POPOVER_OPEN = Duration.millis(170);
    public static final Duration POPOVER_CLOSE = Duration.millis(120);
    public static final Duration DRAFT = Duration.millis(180);
    public static final Duration REDUCED = Duration.millis(40);
    public static final Interpolator ARRIVE = Interpolator.SPLINE(.16, 1, .3, 1);
    /** An analytic damped response for transforms, with a small, bounded settling overshoot.
     * Opacity continues to use the monotonic arrival curve. */
    public static final Interpolator SPRING = new Interpolator() {
        @Override protected double curve(double fraction) {
            if (fraction <= 0) return 0;
            if (fraction >= 1) return 1;
            double damping=.84, frequency=12, under=Math.sqrt(1-damping*damping);
            return 1-Math.exp(-damping*frequency*fraction)
                    *(Math.cos(frequency*under*fraction)+damping/under*Math.sin(frequency*under*fraction));
        }
    };
    /** A critically damped envelope for whole window roots: never grow past
     * the window's viewport, even while content/control springs may settle. */
    public static final Interpolator WINDOW_SPRING = new Interpolator() {
        @Override protected double curve(double fraction) {
            if(fraction<=0)return 0;if(fraction>=1)return 1;
            double frequency=12,terminal=1-(1+frequency)*Math.exp(-frequency);
            return (1-(1+frequency*fraction)*Math.exp(-frequency*fraction))/terminal;
        }
    };
    public static final double SIBLING_SCALE = .986;
    public static final double FORWARD_SCALE = .974;
    public static final double BACK_SCALE = 1.018;
    public static final double TAB_SCALE = .993;
    public static final double SIBLING_Y = 6;
    public static final double FORWARD_X = 22;
    public static final double BACK_X = -18;
    public static final double DRAWER_X = 30;

    private WorkspaceMotionTokens() { }
}
