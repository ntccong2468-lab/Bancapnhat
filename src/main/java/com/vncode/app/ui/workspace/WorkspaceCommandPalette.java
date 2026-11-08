package com.vncode.app.ui.workspace;

import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.List;
import java.util.Locale;

/** Find existing workspace actions; routes retain their original access and lifecycle guards. */
public final class WorkspaceCommandPalette {
    public record Action(String label, Runnable execute) { }
    private Dialog<Void> open;

    public void show(Window owner, List<Action> actions) {
        if (open != null && open.isShowing()) { open.getDialogPane().requestFocus(); return; }
        var tr=I18nService.getInstance();
        var dialog=new Dialog<Void>();open=dialog;
        if(owner!=null)dialog.initOwner(owner);
        dialog.setTitle(tr.tr("header.find_action"));
        var query=new TextField();query.setPromptText(tr.tr("header.find_action")+" · Ctrl+K");
        query.setAccessibleText(tr.tr("header.find_action"));query.setId("workspaceCommandQuery");
        var results=new VBox(4);results.getStyleClass().add("workspace-command-results");
        var scroll=new ScrollPane(results);scroll.setFitToWidth(true);scroll.setPrefViewportHeight(330);
        var content=new VBox(12,query,scroll);content.setPadding(new Insets(12));
        Runnable filter=()->{
            results.getChildren().clear();String term=query.getText().strip().toLowerCase(Locale.ROOT);
            for(var action:actions)if(action.label().toLowerCase(Locale.ROOT).contains(term)){
                var button=new Button(action.label());button.setMaxWidth(Double.MAX_VALUE);
                button.getStyleClass().add("workspace-command-result");
                button.setOnAction(event->{dialog.close();Platform.runLater(action.execute());});
                results.getChildren().add(button);
            }
        };
        query.textProperty().addListener((o,a,b)->filter.run());filter.run();
        query.setOnAction(event->{if(!results.getChildren().isEmpty())((Button)results.getChildren().getFirst()).fire();});
        dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefWidth(620);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        ThemeService.applyTheme(dialog.getDialogPane());
        dialog.setOnShown(event->query.requestFocus());
        dialog.setOnHidden(event->{query.clear();open=null;});dialog.show();
    }
    public void close(){if(open!=null)open.close();}
}
