package com.vncode.app.ui.workspace;
import javafx.scene.Group;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkspaceNavigationTest {
    @Test void nativeCachedPagesPreserveDraftSelectionAndScrollAcrossSiblingNavigation() throws Exception {
        try { javafx.application.Platform.startup(()->javafx.application.Platform.setImplicitExit(false)); }
        catch(IllegalStateException started) { }
        var host=new javafx.scene.layout.BorderPane();
        var list=new javafx.scene.control.ListView<String>();
        var draft=new javafx.scene.control.TextField();
        var scroll=new javafx.scene.control.ScrollPane();
        var page=new javafx.scene.layout.BorderPane();
        var builds=new AtomicInteger();
        var window=new javafx.stage.Stage[1];
        var navigator=new WorkspaceNavigator(host::setCenter);
        // Register suppliers once; revisiting a route must not rebuild controls or lose state.
        navigator.register("products",()->{builds.incrementAndGet();return page;});
        navigator.register("history",()->new javafx.scene.control.Label("Synthetic history"));
        try {
            fx(()->{
                for(int n=0;n<200;n++)list.getItems().add("Synthetic product "+n);
                var longContent=new javafx.scene.layout.VBox();
                for(int n=0;n<120;n++)longContent.getChildren().add(new javafx.scene.control.Label("Synthetic detail "+n));
                scroll.setContent(longContent);scroll.setFitToWidth(true);scroll.setPrefWidth(190);
                page.setTop(draft);page.setCenter(list);page.setRight(scroll);
                window[0]=new javafx.stage.Stage();window[0].setScene(new javafx.scene.Scene(host,900,600));
                navigator.show("products");window[0].show();return null;
            });
            pulse();
            fx(()->{draft.setText("Synthetic unsaved draft");list.getSelectionModel().select(130);list.scrollTo(130);scroll.setVvalue(.65);return null;});
            pulse();
            double tablePosition=fx(()->((javafx.scene.control.ScrollBar)list.lookupAll(".scroll-bar").stream()
                    .filter(n->n instanceof javafx.scene.control.ScrollBar bar&&bar.getOrientation()==javafx.geometry.Orientation.VERTICAL)
                    .findFirst().orElseThrow()).getValue());
            assertTrue(tablePosition>0,"Fixture must really scroll the native list before leaving");
            fx(()->{navigator.show("history");return null;});pulse();
            fx(()->{navigator.show("products");return null;});pulse();
            fx(()->{
                assertSame(page,host.getCenter());assertSame(list,page.getCenter());assertEquals(1,builds.get());
                assertEquals("Synthetic unsaved draft",draft.getText());assertEquals(130,list.getSelectionModel().getSelectedIndex());
                assertEquals(.65,scroll.getVvalue(),.001,"Cached page keeps its own scroll state");
                var bar=(javafx.scene.control.ScrollBar)list.lookupAll(".scroll-bar").stream()
                        .filter(n->n instanceof javafx.scene.control.ScrollBar value&&value.getOrientation()==javafx.geometry.Orientation.VERTICAL)
                        .findFirst().orElseThrow();
                assertEquals(tablePosition,bar.getValue(),.001,"Returning must not reset the virtualized list");return null;
            });
        } finally {fx(()->{if(window[0]!=null)window[0].close();return null;});}
    }
    private static <T>T fx(java.util.concurrent.Callable<T> call)throws Exception {
        var task=new java.util.concurrent.FutureTask<>(call);javafx.application.Platform.runLater(task);
        return task.get(15,java.util.concurrent.TimeUnit.SECONDS);
    }
    private static void pulse()throws Exception {
        var finished=new java.util.concurrent.CompletableFuture<Void>();
        fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();finished.complete(null);}}}.start();return null;});
        finished.get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
    @Test void showingKnownViewDoesNotConstructUnopenedRoutes() {
        var navigator=new WorkspaceNavigator(n->{});var builds=new AtomicInteger();
        var known=new Group();navigator.register("known",()->known);navigator.show("known");
        navigator.register("heavy",()->{builds.incrementAndGet();return new Group();});
        navigator.showView(known);assertEquals(0,builds.get());
        assertThrows(IllegalArgumentException.class,()->navigator.showView(new Group()));assertEquals(0,builds.get());
    }
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
