package com.vncode.app.ui.workspace;
import javafx.scene.Group;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkspaceNavigationTest {
    @Test void keepsAllRoutesAndReusesViews() {
        var displayed=new AtomicReference<javafx.scene.Node>();var builds=new AtomicInteger();
        var navigator=new WorkspaceNavigator(displayed::set);
        String[] routes={"finance","supplies","packing","fbo-packing","fbo-orders","ozon","kiz-mapping","znack","znack-registration","print-history","gtin-sync"};
        for(String route:routes)navigator.register(route,()->{builds.incrementAndGet();return new Group();});
        for(String route:routes){navigator.show(route);assertEquals(route,navigator.currentRoute());}
        assertEquals(routes.length,builds.get());navigator.show("gtin-sync");assertEquals(routes.length,builds.get());
        assertNotNull(displayed.get());
    }
    @Test void appliesAccessGuardBeforeLoadingAndRejectsUnknownRoutes() {
        var navigator=new WorkspaceNavigator(n->{});var builds=new AtomicInteger();
        navigator.register("restricted",()->{builds.incrementAndGet();return new Group();});
        navigator.setAccessGuard(route->false);
        assertThrows(SecurityException.class,()->navigator.show("restricted"));assertEquals(0,builds.get());
        assertThrows(IllegalArgumentException.class,()->navigator.show("missing"));
    }
}
