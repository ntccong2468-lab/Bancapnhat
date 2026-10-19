package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.KizSourceSelection;
import com.vncode.app.features.kiz.status.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import java.util.concurrent.*;
import java.util.function.*;

/** The report closes before mutation starts. Only verified completion changes the pill text. */
public final class Kn3BatchDialog {
    private Kn3BatchDialog(){}
    public static boolean running(StackPane host){return host!=null&&host.getProperties().containsKey(Kn3BatchDialog.class);}
    public static void clear(StackPane host){if(host==null)return;host.getProperties().remove(Kn3BatchDialog.class);host.getChildren().removeIf(n->"kn3BatchPill".equals(n.getId()));setButtonBusy(host,false);}
    private static void setButtonBusy(StackPane host,boolean busy){if(host.getScene()!=null&&host.getScene().lookup("#addAgainKizButton") instanceof Button button)button.setDisable(busy);}
    public static void open(StackPane host,Shop shop,Kn3BatchReplacement.Report report,KizSourceSelection sources,Runnable refresh){
        open(host,shop,report,()->new Kn3BatchReplacement().start(shop,report,sources),refresh);
    }
    static void open(StackPane host,Shop shop,Kn3BatchReplacement.Report report,Supplier<CompletableFuture<Void>> action,Runnable refresh){
        if(report.rows().isEmpty()||running(host))return;
        var dialog=new Dialog<Boolean>();dialog.setTitle(tr("batch.title"));if(host.getScene()!=null)dialog.initOwner(host.getScene().getWindow());
        var table=new TableView<Kn3BatchReplacement.Row>(javafx.collections.FXCollections.observableArrayList(report.rows()));table.setId("kn3RejectedOrdersReport");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,tr("batch.order"),r->r.mark().orderId(),140);column(table,"Article / SKU",r->r.article()+" / "+r.barcode(),220);column(table,"KN3",r->"KN3 · "+I18nService.getInstance().tr("kn3.reason."+r.mark().reason(),r.mark().reason()),310);
        table.setMinHeight(160);table.setPrefHeight(360);VBox.setVgrow(table,Priority.ALWAYS);var note=new Label(tr("batch.notice"));note.setWrapText(true);note.setMinHeight(Region.USE_PREF_SIZE);note.setMaxWidth(Double.MAX_VALUE);dialog.getDialogPane().setContent(new VBox(12,note,table));dialog.getDialogPane().setPrefWidth(760);
        var confirm=new ButtonType(tr("batch.confirm"),ButtonBar.ButtonData.OK_DONE);var cancel=new ButtonType(tr("batch.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE);dialog.getDialogPane().getButtonTypes().setAll(confirm,cancel);
        var confirmButton=(Button)dialog.getDialogPane().lookupButton(confirm);confirmButton.setId("kn3BatchConfirm");confirmButton.setMinWidth(110);confirmButton.setPrefWidth(110);var cancelButton=(Button)dialog.getDialogPane().lookupButton(cancel);cancelButton.setId("kn3BatchCancel");cancelButton.setMinWidth(90);dialog.setResultConverter(b->b==confirm);
        DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        dialog.showAndWait().filter(Boolean.TRUE::equals).ifPresent(yes->submit(host,action,refresh));
    }
    private static void column(TableView<Kn3BatchReplacement.Row> table,String title,Function<Kn3BatchReplacement.Row,String> value,int width){var c=new TableColumn<Kn3BatchReplacement.Row,String>(title);c.setPrefWidth(width);c.setCellValueFactory(v->new ReadOnlyStringWrapper(value.apply(v.getValue())));c.setCellFactory(column->new TableCell<>(){final Label label=new Label();{label.setWrapText(true);label.setMinWidth(0);if(title.equals("KN3"))label.getStyleClass().addAll("badge","badge-red");label.maxWidthProperty().bind(column.widthProperty().subtract(20));}protected void updateItem(String text,boolean empty){super.updateItem(text,empty);setText(null);label.setText(empty?"":text);label.setTooltip(empty?null:new Tooltip(text));setGraphic(empty?null:label);}});table.getColumns().add(c);}
    private static void submit(StackPane host,Supplier<CompletableFuture<Void>> action,Runnable refresh){
        var token=new Object();host.getProperties().put(Kn3BatchDialog.class,token);setButtonBusy(host,true);
        host.getChildren().removeIf(n->"kn3BatchPill".equals(n.getId()));var text=new Label(tr("batch.running"));var spinner=new ProgressIndicator();spinner.setMaxSize(18,18);var pill=new HBox(10,spinner,text);pill.setId("kn3BatchPill");pill.getStyleClass().add("task-status-pill");pill.setAlignment(Pos.CENTER);pill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new Insets(12));host.getChildren().add(pill);
        CompletableFuture<Void> future;try{future=action.get();}catch(Throwable e){future=CompletableFuture.failedFuture(e);}
        future.whenComplete((v,e)->Platform.runLater(()->{if(host.getProperties().get(Kn3BatchDialog.class)!=token)return;host.getProperties().remove(Kn3BatchDialog.class);setButtonBusy(host,false);refresh.run();if(e==null){pill.getChildren().remove(spinner);text.setText(tr("batch.done"));}else{host.getChildren().remove(pill);AlertService.showDetailedError(tr("batch.incomplete"),FriendlyErrorService.format(e)+"\n"+details(e));}}));
    }
    private static String details(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();var text=new StringBuilder();for(String line:java.util.Objects.toString(error.getMessage(),"").split("\\R")){int separator=line.indexOf(": ");String code=separator<0?line:line.substring(separator+2);String prefix=separator<0?"":line.substring(0,separator+2);if(code.startsWith("KIZ_"))text.append(prefix).append(com.vncode.app.features.kiz.KizErrorMessages.message(code)).append('\n');else text.append(line).append('\n');}return text.toString();}
    private static String tr(String key){return I18nService.getInstance().tr("kn3."+key);}
}
