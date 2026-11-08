package com.vncode.app.ui.workspace;

import com.vncode.app.shared.NavigationContext;
import javafx.scene.Group;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceNavigationContextTest {
    @Test void contextComesFromTheActionAndCachedViewsKeepTheirIdentity() {
        var context = new AtomicReference<NavigationContext>();
        var displayed = new AtomicReference<Node>();
        var builds = new AtomicInteger();
        var navigator = new WorkspaceNavigator((node, relationship) -> { displayed.set(node); context.set(relationship); });
        navigator.register("products", () -> { builds.incrementAndGet(); return new Group(); });
        navigator.register("child", () -> { builds.incrementAndGet(); return new Group(); });
        navigator.show("products");
        Node products = displayed.get();
        assertEquals(NavigationContext.SIBLING, context.get());
        navigator.show("child", NavigationContext.FORWARD);
        assertEquals(NavigationContext.FORWARD, context.get());
        navigator.showView(products, NavigationContext.BACK);
        assertSame(products, displayed.get());
        assertEquals(NavigationContext.BACK, context.get());
        assertEquals("products", navigator.currentRoute());
        assertEquals(2, builds.get());
    }

    @Test void contextualNavigationStillGuardsBeforeLoadingOrDisplaying() {
        var builds = new AtomicInteger();
        var displays = new AtomicInteger();
        var navigator = new WorkspaceNavigator((node, relationship) -> displays.incrementAndGet());
        navigator.register("restricted", () -> { builds.incrementAndGet(); return new Group(); });
        navigator.setAccessGuard(route -> false);
        assertThrows(SecurityException.class, () -> navigator.show("restricted", NavigationContext.FORWARD));
        assertEquals(0, builds.get());
        assertEquals(0, displays.get());
        assertEquals("", navigator.currentRoute());
    }
}
