package com.vncode.app.ui.workspace;

import com.vncode.app.features.jobs.JobCenterRepository;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.function.Consumer;
import java.util.function.Function;

public final class JobCenterDialog {
    private JobCenterDialog() {}
    public static void show(Window owner,Consumer<JobCenterRepository.Row> open) {
        var dialog=new Dialog<Void>();dialog.setTitle(tr("jobs.title"));if(owner!=null)dialog.initOwner(owner);
        var table=new TableView<JobCenterRepository.Row>();table.setPrefHeight(350);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,tr("jobs.shop"),JobCenterRepository.Row::shop);column(table,tr("jobs.kind"),r->tr("jobs.kind."+r.kind()));column(table,tr("znack.registration.status"),r->tr("jobs.state."+r.state()));column(table,tr("jobs.count"),r->Long.toString(r.count()));
        var status=new Label(tr("jobs.loading"));status.setWrapText(true);var refresh=new Button(tr("znack.registration.refresh"));var go=new Button(tr("jobs.open"));go.setDisable(true);
        Runnable reload=()->{refresh.setDisable(true);AppTaskExecutor.execute(new javafx.concurrent.Task<java.util.List<JobCenterRepository.Row>>() {
            protected java.util.List<JobCenterRepository.Row> call(){return new JobCenterRepository().load();}
            protected void succeeded(){refresh.setDisable(false);table.getItems().setAll(getValue());status.setText(tr("jobs.hint"));}
            protected void failed(){refresh.setDisable(false);status.setText(tr("jobs.read_failed"));}
        },e->{refresh.setDisable(false);status.setText(tr("jobs.read_failed"));});};
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->go.setDisable(b==null));refresh.setOnAction(e->reload.run());go.setOnAction(e->{var row=table.getSelectionModel().getSelectedItem();if(row!=null){dialog.close();open.accept(row);}});
        dialog.getDialogPane().setContent(new VBox(12,status,table,new javafx.scene.layout.HBox(10,refresh,go)));dialog.getDialogPane().setPrefWidth(850);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);reload.run();dialog.show();
    }
    private static void column(TableView<JobCenterRepository.Row> table,String title,Function<JobCenterRepository.Row,String> text){var c=new TableColumn<JobCenterRepository.Row,String>(title);c.setCellValueFactory(v->new ReadOnlyStringWrapper(text.apply(v.getValue())));table.getColumns().add(c);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
