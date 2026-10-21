package com.vncode.app.ui.fbo;

import com.vncode.app.features.fbo.*;
import com.vncode.app.shared.I18nService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.List;
import java.util.function.Function;
import java.util.function.Consumer;

/** Display the exact frozen subset that the seller authorizes for printing. */
public final class FboPreflightDialog {
    private FboPreflightDialog() {}
    public static List<FboBarcodePrintItem> review(FboPreflightService.Result result,Window owner,Runnable openRegistration,Consumer<FboPreflightService.Result> buyMissing) {
        var dialog=new Dialog<List<FboBarcodePrintItem>>();dialog.setTitle(tr("fbo.preflight.title"));if(owner!=null)dialog.initOwner(owner);
        var table=new TableView<FboPreflightService.Row>();table.getItems().setAll(result.rows());
        column(table,tr("fbo.column.name"),r->r.item().product().title()+" · "+r.item().product().vendorCode());
        column(table,tr("fbo.column.size"),r->r.item().product().size());column(table,"GTIN",FboPreflightService.Row::gtin);
        column(table,tr("fbo.preflight.need"),r->Integer.toString(r.item().quantity()));
        column(table,tr("fbo.preflight.have"),r->Integer.toString(r.available()));
        column(table,tr("fbo.preflight.buy"),r->Integer.toString(r.toBuy()));
        column(table,tr("znack.registration.status"),r->tr("fbo.preflight."+r.state().name()));
        table.setPrefHeight(350);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var note=new Label(java.text.MessageFormat.format(tr("fbo.preflight.summary"),result.ready().size(),result.rows().size()));note.setWrapText(true);
        var demand=new Label(tr("fbo.preflight.total_buy")+": "+result.toBuy());demand.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(12,note,table,demand));dialog.getDialogPane().setPrefWidth(900);
        var print=new ButtonType(tr("fbo.preflight.print_ready"),ButtonBar.ButtonData.OK_DONE);
        var registration=new ButtonType(tr("fbo.preflight.registration"),ButtonBar.ButtonData.LEFT);
        var buy=new ButtonType(tr("fbo.preflight.buy_missing"),ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().setAll(print,buy,registration,ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(print).setDisable(result.ready().isEmpty());
        dialog.getDialogPane().lookupButton(buy).setDisable(result.toBuy().isEmpty());
        dialog.setResultConverter(button->{if(button==registration){openRegistration.run();return List.of();}if(button==buy){buyMissing.accept(result);return List.of();}return button==print?result.ready():List.of();});
        return dialog.showAndWait().orElse(List.of());
    }
    private static void column(TableView<FboPreflightService.Row> table,String name,Function<FboPreflightService.Row,String> value){var c=new TableColumn<FboPreflightService.Row,String>(name);c.setCellValueFactory(v->new ReadOnlyStringWrapper(value.apply(v.getValue())));table.getColumns().add(c);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
