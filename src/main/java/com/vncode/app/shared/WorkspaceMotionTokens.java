package com.vncode.app.shared;

import javafx.animation.Interpolator;
import javafx.util.Duration;

/** Short, monotonic feedback for the approved flat desktop workspace. */
public final class WorkspaceMotionTokens {
    public static final Duration SIBLING = Duration.millis(160);
    public static final Duration HIERARCHY = Duration.millis(200);
    public static final Duration TAB = Duration.millis(120);
    public static final Duration PAGE_EXIT = Duration.millis(120);
    public static final Duration DRAWER_OPEN = Duration.millis(180);
    public static final Duration DRAWER_CLOSE = Duration.millis(120);
    public static final Duration MODAL_OPEN = Duration.millis(180);
    public static final Duration MODAL_CLOSE = Duration.millis(120);
    public static final Duration POPOVER_OPEN = Duration.millis(160);
    public static final Duration POPOVER_CLOSE = Duration.millis(100);
    public static final Duration DRAFT = Duration.millis(160);
    public static final Duration REDUCED = Duration.millis(40);
    public static final Interpolator ARRIVE = Interpolator.SPLINE(.22, 1, .36, 1);
    public static final double FORWARD_X = 12;
    public static final double BACK_X = -12;
    public static final double DRAWER_X = 16;

    private WorkspaceMotionTokens() { }
}
