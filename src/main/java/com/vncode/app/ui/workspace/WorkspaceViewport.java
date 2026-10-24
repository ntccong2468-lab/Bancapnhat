package com.vncode.app.ui.workspace;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.Region;

/** Shared work-area viewport. Tables yield space to actions; short windows can scroll the whole page. */
public final class WorkspaceViewport extends ScrollPane {
    public WorkspaceViewport(Node page) {
        setMinSize(0, 0);
        setFitToWidth(true);
        setFitToHeight(true);
        setPannable(false);
        setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        getStyleClass().addAll("edge-to-edge", "workspace-viewport");
        prepare(page);
        if (page instanceof Region region && !region.minHeightProperty().isBound())
            region.setMinHeight(Region.USE_COMPUTED_SIZE);
        setContent(page);
    }

    private static void prepare(Node node) {
        if(node.getProperties().putIfAbsent(WorkspaceViewport.class,Boolean.TRUE)!=null)return;
        // Keep at least a few visible rows. The remaining height belongs to headers and actions.
        if (node instanceof TableView<?> || node instanceof TreeTableView<?> || node instanceof ListView<?>
                || node instanceof TreeView<?>) {
            var region = (Region) node;
            if (!region.minHeightProperty().isBound()) region.setMinHeight(80);
            return;
        }
        if (node instanceof ScrollPane scroll) {
            if (!scroll.minHeightProperty().isBound()) scroll.setMinHeight(0);
            // This page already owns its scrolling and must retain its content's natural height.
            return;
        }
        if (node instanceof TabPane tabs) {
            tabs.getTabs().forEach(WorkspaceViewport::prepareTab);
            tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
                while (change.next()) for (var tab : change.getAddedSubList())
                    prepareTab(tab);
            });
            return;
        }
        if (node instanceof SplitPane split) { split.getItems().forEach(WorkspaceViewport::prepare); return; }
        if (node instanceof TitledPane titled) { if (titled.getContent() != null) prepare(titled.getContent()); return; }
        // Only walk application layout nodes, never a Control's skin/virtualized rows.
        if (node instanceof Parent parent && !(node instanceof Control)) {
            if(node instanceof Region region&&!region.minHeightProperty().isBound()&&region.getMinHeight()==0)
                region.setMinHeight(Region.USE_COMPUTED_SIZE);
            parent.getChildrenUnmodifiable().forEach(WorkspaceViewport::prepare);
            parent.getChildrenUnmodifiable().addListener((javafx.collections.ListChangeListener<Node>) change->{
                while(change.next())change.getAddedSubList().forEach(WorkspaceViewport::prepare);
            });
        }
    }
    private static void prepareTab(Tab tab) {
        if(tab.getContent()!=null)prepare(tab.getContent());
        tab.contentProperty().addListener((observable,old,value)->{if(value!=null)prepare(value);});
    }
}
