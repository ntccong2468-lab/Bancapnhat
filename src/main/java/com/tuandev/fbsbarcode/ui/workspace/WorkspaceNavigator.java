package com.tuandev.fbsbarcode.ui.workspace;
import javafx.scene.Node;
import java.util.*;
import java.util.function.*;

/** Central navigation reuses existing views and leaves business/lifecycle hooks with the host. */
public final class WorkspaceNavigator {
    private final Map<String,Supplier<Node>> routes=new LinkedHashMap<>();
    private final Map<String,Node> cache=new HashMap<>();
    private final Consumer<Node> display;
    private Predicate<String> access=route->true;
    private String current="";
    public WorkspaceNavigator(Consumer<Node> display){this.display=Objects.requireNonNull(display);}
    public void register(String route,Supplier<Node> view) {
        if(route==null||route.isBlank()||routes.containsKey(route))throw new IllegalArgumentException("invalid_route");
        routes.put(route,Objects.requireNonNull(view));
    }
    public void setAccessGuard(Predicate<String> access){this.access=Objects.requireNonNull(access);}
    public void show(String route) {
        var factory=routes.get(route);if(factory==null)throw new IllegalArgumentException("unknown_route");
        if(!access.test(route))throw new SecurityException("route_denied");
        var view=cache.computeIfAbsent(route,key->Objects.requireNonNull(factory.get(),"View not initialized"));
        display.accept(view);current=route;
    }
    public void showView(Node view) {
        Objects.requireNonNull(view);
        for(var route:routes.entrySet()) {
            if(cache.get(route.getKey())==view){show(route.getKey());return;}
            if(!cache.containsKey(route.getKey()) && route.getValue().get()==view){cache.put(route.getKey(),view);show(route.getKey());return;}
        }
        throw new IllegalArgumentException("unregistered_view");
    }
    public String currentRoute(){return current;}
}
