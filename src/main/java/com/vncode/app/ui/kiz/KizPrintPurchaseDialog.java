package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.KizPrintPurchaseSession;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** The export worker asks on FX, then resumes only after Apply has closed the dialog. */
public final class KizPrintPurchaseDialog {
    private KizPrintPurchaseDialog(){}
    public static boolean confirm(Shop shop,KizPrintPurchaseSession.Proposal proposal,BooleanSupplier cancelled){
        if(Platform.isFxApplicationThread())throw new IllegalStateException("Print worker required");
        var answer=new CompletableFuture<Boolean>();var shown=new AtomicReference<Dialog<Boolean>>();
        Platform.runLater(()->{
            if(cancelled.getAsBoolean()||answer.isDone()){answer.complete(false);return;}
            try{var dialog=create(shop,proposal);shown.set(dialog);answer.complete(dialog.showAndWait().orElse(false));}
            catch(Throwable error){answer.completeExceptionally(error);}
        });
        try{
            while(true){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||UpdateHandoff.isStopping()||UpdateHandoff.requested())throw new CancellationException();
                try{return answer.get(200,TimeUnit.MILLISECONDS);}catch(TimeoutException waiting){/* Keep cancellation responsive. */}
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException();}
        catch(ExecutionException failed){throw new IllegalStateException("KIZ_PRINT_PROPOSAL_FAILED",failed.getCause());}
        finally{answer.complete(false);Platform.runLater(()->{var dialog=shown.get();if(dialog!=null&&dialog.isShowing())dialog.close();});}
    }
    public static Dialog<Boolean> create(Shop shop,KizPrintPurchaseSession.Proposal proposal){
        var dialog=new Dialog<Boolean>();dialog.setTitle(tr("title"));dialog.setHeaderText(shop.getName());
        var note=new Label(java.text.MessageFormat.format(tr("confirm"),proposal.quantity()));note.setWrapText(true);note.setMinHeight(Region.USE_PREF_SIZE);
        var table=new TableView<KizPrintPurchaseSession.Row>(javafx.collections.FXCollections.observableArrayList(proposal.rows()));
        table.setId("kizPrintPurchaseReport");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,"GTIN",KizPrintPurchaseSession.Row::gtin,200);
        column(table,tr("required"),r->""+r.required(),100);column(table,tr("available"),r->""+r.available(),110);
        column(table,tr("missing"),r->""+r.missing(),100);column(table,tr("buy"),r->""+r.buy(),100);
        table.setMinHeight(130);table.setPrefHeight(300);VBox.setVgrow(table,Priority.ALWAYS);
        var body=new VBox(12,note,table);dialog.getDialogPane().setContent(body);dialog.getDialogPane().setPrefSize(760,440);
        var apply=new ButtonType(tr("apply"),ButtonBar.ButtonData.OK_DONE);
        var cancel=new ButtonType(I18nService.getInstance().tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(apply,cancel);dialog.getDialogPane().lookupButton(apply).setId("kizPrintPurchaseApply");dialog.getDialogPane().lookupButton(cancel).setId("kizPrintPurchaseCancel");
        dialog.setResultConverter(button->button==apply);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);return dialog;
    }
    private static void column(TableView<KizPrintPurchaseSession.Row> table,String title,Function<KizPrintPurchaseSession.Row,String> value,int width){
        var column=new TableColumn<KizPrintPurchaseSession.Row,String>(title);column.setPrefWidth(width);column.setCellValueFactory(row->new ReadOnlyStringWrapper(value.apply(row.getValue())));table.getColumns().add(column);
    }
    private static String tr(String key){return I18nService.getInstance().tr("kiz.print."+key);}
}
