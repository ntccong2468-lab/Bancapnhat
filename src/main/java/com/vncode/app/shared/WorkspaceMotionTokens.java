package com.vncode.app.shared;

import javafx.animation.Interpolator;
import javafx.util.Duration;

/** Native counterparts of the approved desktop motion tokens. */
public final class WorkspaceMotionTokens {
    public static final Duration SIBLING = Duration.millis(200);
    public static final Duration HIERARCHY = Duration.millis(260);
    public static final Duration TAB = Duration.millis(150);
    public static final Duration DRAWER_OPEN = Duration.millis(220);
    public static final Duration DRAWER_CLOSE = Duration.millis(130);
    public static final Duration MODAL_OPEN = Duration.millis(240);
    public static final Duration POPOVER_OPEN = Duration.millis(170);
    public static final Duration DRAFT = Duration.millis(180);
    public static final Duration REDUCED = Duration.millis(40);
    public static final Interpolator ARRIVE = Interpolator.SPLINE(.16, 1, .3, 1);
    public static final double SIBLING_Y = 6;
    public static final double FORWARD_X = 22;
    public static final double BACK_X = -18;
    public static final double DRAWER_X = 30;

    private WorkspaceMotionTokens() { }
}
