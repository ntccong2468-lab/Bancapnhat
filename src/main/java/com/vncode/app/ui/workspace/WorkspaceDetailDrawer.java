package com.vncode.app.ui.workspace;

import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.WorkspaceMotion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import java.util.List;
import java.util.Objects;

/** Read-only details over the current list; opening never replaces its data or selection. */
public final class WorkspaceDetailDrawer {
    public record Field(String label, String value) { }
    public record Section(String title, List<Field> fields) {
        public Section { fields = List.copyOf(fields); }
    }
    private final StackPane host;
    private final VBox panel=new VBox(14);
    private final Label heading=new Label();
    private final VBox details=new VBox(18);
    private final ScrollPane scroll=new ScrollPane(details);
    private final Button close=new Button("",new FontIcon("fth-x"));
    private Node returnFocus;
    private long generation;

    public WorkspaceDetailDrawer(StackPane host) {
        this.host=host;panel.setId("workspaceDetailDrawer");panel.getStyleClass().add("detail-drawer");
        panel.setMinWidth(0);panel.setPrefWidth(420);panel.maxWidthProperty().bind(host.widthProperty().multiply(.9).map(width->Math.min(480,width.doubleValue())));
        StackPane.setAlignment(panel,Pos.CENTER_RIGHT);
        heading.getStyleClass().add("h3");heading.setWrapText(true);heading.setMinWidth(0);heading.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(heading,Priority.ALWAYS);
        close.setId("closeWorkspaceDetail");close.setMinSize(34,34);close.setAccessibleText(I18nService.getInstance().tr("ui.detail.close"));
        var header=new HBox(12,heading,close);header.setAlignment(Pos.CENTER_LEFT);
        details.setFillWidth(true);
        scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);VBox.setVgrow(scroll,Priority.ALWAYS);
        panel.getChildren().setAll(header,scroll);close.setOnAction(e->close());
        panel.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ESCAPE){close();e.consume();}});
        host.sceneProperty().addListener((o,previous,scene)->{if(scene==null)clear();});
    }
    public void show(String title,List<Section> sections) {
        ++generation;
        if(!host.getChildren().contains(panel)){
            returnFocus=host.getScene()==null?null:host.getScene().getFocusOwner();host.getChildren().add(panel);
        }
        heading.setText(title);details.getChildren().clear();
        for (Section section : sections) {
            var group=new VBox(10);group.getStyleClass().add("detail-group");
            if(section.title()!=null&&!section.title().isBlank()){
                var label=new Label(section.title());label.setWrapText(true);
                label.getStyleClass().add("text-bold");group.getChildren().add(label);
            }
            for(Field field:section.fields()){
                var label=new Label(field.label());label.getStyleClass().add("text-muted");
                String content=Objects.toString(field.value(),"");
                var value=new Label(content.isBlank()?"—":content);value.setWrapText(true);value.setMaxWidth(Double.MAX_VALUE);
                value.setAccessibleText(field.label()+": "+value.getText());value.setFocusTraversable(true);
                Runnable copy=()->{var clipboard=new ClipboardContent();clipboard.putString(content);Clipboard.getSystemClipboard().setContent(clipboard);};
                var item=new MenuItem(I18nService.getInstance().tr("common.copy"));item.setOnAction(e->copy.run());
                value.setContextMenu(new ContextMenu(item));
                value.setOnKeyPressed(e->{if(e.isShortcutDown()&&e.getCode()==KeyCode.C){copy.run();e.consume();}});
                group.getChildren().add(new VBox(3,label,value));
            }
            details.getChildren().add(group);
        }
        scroll.setVvalue(0);panel.toFront();
        WorkspaceMotion.drawer(panel,true,()->{});close.requestFocus();
    }
    public void close() {
        long epoch=++generation;
        WorkspaceMotion.drawer(panel,false,()->{
            if(epoch!=generation)return;
            host.getChildren().remove(panel);
            if(returnFocus!=null&&returnFocus.getScene()==host.getScene())returnFocus.requestFocus();
            returnFocus=null;
        });
    }
    public void clear(){++generation;WorkspaceMotion.stop(panel);host.getChildren().remove(panel);details.getChildren().clear();returnFocus=null;}
}
