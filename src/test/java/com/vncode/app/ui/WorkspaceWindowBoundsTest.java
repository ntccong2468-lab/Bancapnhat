package com.vncode.app.ui;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceWindowBoundsTest {
    @Test void splashPositionCannotPushTheLargerWorkspaceUnderTheTaskbar() {
        var actual=WorkspaceWindowBounds.fit(new Rectangle2D(0,0,1366,720),320,250,1300,740);
        assertEquals(new Rectangle2D(66,0,1300,720),actual);
    }
    @Test void preservesAnAlreadyVisibleWindowAndSupportsNegativeMonitorCoordinates() {
        var work=new Rectangle2D(-1920,40,1920,1000);
        assertEquals(new Rectangle2D(-1700,100,1300,700),WorkspaceWindowBounds.fit(work,-1700,100,1300,700));
        assertEquals(new Rectangle2D(-1920,40,1920,1000),WorkspaceWindowBounds.fit(work,-2000,-100,2200,1200));
    }
    @Test void aTaskbarOnTheLeftAndBottomIsExcluded() {
        var work=new Rectangle2D(72,0,1208,752);
        var actual=WorkspaceWindowBounds.fit(work,0,100,1280,800);
        assertEquals(work,actual);
    }
}
