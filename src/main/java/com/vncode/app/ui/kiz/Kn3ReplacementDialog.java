package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.status.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;

/** The dialog closes on Apply; the accepted task continues in a nonmodal bottom status pill. */
public final class Kn3ReplacementDialog {
    private Kn3ReplacementDialog(){}
    private record Choice(Kn3Mark mark,String code){@Override public String toString(){return "Kn3ReplacementChoice[redacted]";}}
    public static void open(StackPane host,Shop owner,List<Kn3Mark> marks,Runnable refresh){
        var rejected=marks.stream().filter(m->m.state()==Kn3Mark.State.REJECTED).toList();if(rejected.isEmpty()||owner==null||host==null)return;
        var shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());
        var dialog=new Dialog<Choice>();dialog.setTitle(tr("replace"));if(host.getScene()!=null&&host.getScene().getWindow()!=null)dialog.initOwner(host.getScene().getWindow());
        var target=new ComboBox<Kn3Mark>(javafx.collections.FXCollections.observableArrayList(rejected));target.setId("kn3ReplacementTarget");target.setMaxWidth(Double.MAX_VALUE);target.setConverter(new StringConverter<>(){public String toString(Kn3Mark mark){return mark==null?"":mark.orderId()+(mark.productId().isBlank()?"":" · SKU "+mark.productId()+" · #"+mark.exemplarId());}public Kn3Mark fromString(String s){return null;}});target.getSelectionModel().selectFirst();
        var reason=new Label();reason.setWrapText(true);reason.setId("kn3ReplacementReason");target.valueProperty().addListener((o,a,b)->reason.setText(b==null?"":I18nService.getInstance().tr("kn3.reason."+b.reason(),b.reason())));reason.setText(I18nService.getInstance().tr("kn3.reason."+target.getValue().reason(),target.getValue().reason()));
        var input=new TextArea();input.setId("kn3ReplacementCode");input.setPromptText(tr("new_code"));input.setPrefRowCount(3);input.setWrapText(true);
        var notice=new Label(tr("confirmation"));notice.setWrapText(true);var content=new VBox(12,target,reason,input,notice);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefWidth(560);
        var apply=new ButtonType(tr("apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);var button=(Button)dialog.getDialogPane().lookupButton(apply);button.setId("kn3ReplacementApply");button.getStyleClass().add("btn-primary");button.disableProperty().bind(input.textProperty().isEmpty());
        dialog.setResultConverter(b->b==apply?new Choice(target.getValue(),input.getText()):null);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        dialog.showAndWait().ifPresent(choice->submit(host,shop,choice,refresh));
    }
    private static void submit(StackPane host,Shop shop,Choice choice,Runnable refresh){
        host.getChildren().removeIf(n->"kn3ReplacementPill".equals(n.getId()));var text=new Label(tr("sending"));text.setWrapText(true);var spinner=new ProgressIndicator();spinner.setMaxSize(18,18);var pill=new HBox(10,spinner,text);pill.setId("kn3ReplacementPill");pill.getStyleClass().add("task-status-pill");pill.setAlignment(Pos.CENTER);pill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new Insets(12));host.getChildren().add(pill);
        SellerMutationExecutor.submit(shop,()->{new Kn3ReplacementService().replace(shop,choice.mark(),choice.code());return true;}).whenComplete((value,error)->Platform.runLater(()->{
            pill.getChildren().remove(spinner);text.setText(error==null?tr("awaiting"):FriendlyErrorService.format(error));if(error!=null){var details=new Button(tr("details"));details.setOnAction(e->AlertService.showDetailedError(text.getText(),com.vncode.app.integration.znack.ZnackErrorDetails.format(error)));pill.getChildren().add(details);}refresh.run();
            com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.FBS_STATUS);
        }));
    }
    private static String tr(String key){return I18nService.getInstance().tr("kn3."+key);}
}
