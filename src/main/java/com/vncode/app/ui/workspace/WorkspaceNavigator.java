package com.vncode.app.ui.workspace;
import com.vncode.app.shared.NavigationContext;
import javafx.scene.Node;
import java.util.*;
import java.util.function.*;

/** Central navigation reuses existing views and leaves business/lifecycle hooks with the host. */
public final class WorkspaceNavigator {
    private final Map<String,Supplier<Node>> routes=new LinkedHashMap<>();
    private final Map<String,Node> cache=new HashMap<>();
    private final BiConsumer<Node,NavigationContext> display;
    private Predicate<String> access=route->true;
    private String current="";
    public WorkspaceNavigator(Consumer<Node> display){
        Objects.requireNonNull(display);this.display=(view,context)->display.accept(view);
    }
    public WorkspaceNavigator(BiConsumer<Node,NavigationContext> display){this.display=Objects.requireNonNull(display);}
    public void register(String route,Supplier<Node> view) {
        if(route==null||route.isBlank()||routes.containsKey(route))throw new IllegalArgumentException("invalid_route");
        routes.put(route,Objects.requireNonNull(view));
    }
    public void setAccessGuard(Predicate<String> access){this.access=Objects.requireNonNull(access);}
    public void show(String route) {
        show(route,NavigationContext.SIBLING);
    }
    public void show(String route,NavigationContext context) {
        Objects.requireNonNull(context);
        var factory=routes.get(route);if(factory==null)throw new IllegalArgumentException("unknown_route");
        if(!access.test(route))throw new SecurityException("route_denied");
        var view=cache.computeIfAbsent(route,key->Objects.requireNonNull(factory.get(),"View not initialized"));
        if(!view.getStyleClass().contains("app-page"))view.getStyleClass().add("app-page");
        if(view instanceof javafx.scene.layout.Region region){region.setMinWidth(0);}
        display.accept(view,context);current=route;
    }
    public void showView(Node view) {
        showView(view,NavigationContext.SIBLING);
    }
    public void showView(Node view,NavigationContext context) {
        Objects.requireNonNull(view);
        for(var route:routes.entrySet()) {
            if(cache.get(route.getKey())==view){show(route.getKey(),context);return;}
        }
        throw new IllegalArgumentException("unregistered_view");
    }
    public String currentRoute(){return current;}
}
