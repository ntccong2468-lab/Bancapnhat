package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.DialogLayout;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;

/** Reviews literal schema fields and document selection before authorizing one native Ozon card. */
final class OzonRegistrationReviewDialog {
    private OzonRegistrationReviewDialog(){}
    private record Input(Attribute attribute,Control value,ComboBox<String> type){}
    static Draft review(OzonCardRegistrationWorkflow.Preparation prepared) {
        var dialog=new Dialog<Draft>();dialog.setTitle(tr("znack.registration.ozon_review_title"));
        var focused=javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isFocused).findFirst().orElse(null);if(focused!=null)dialog.initOwner(focused);
        var warning=new Label(tr("znack.registration.ozon_gs1_warning"));warning.setWrapText(true);warning.setMinWidth(0);warning.setMaxWidth(Double.MAX_VALUE);dialog.getDialogPane().setHeader(warning);
        var grid=new GridPane();grid.setMinWidth(0);grid.setHgap(10);grid.setVgap(10);
        var labels=new ColumnConstraints();labels.setMinWidth(90);labels.setPrefWidth(180);labels.setMaxWidth(200);
        var valuesColumn=new ColumnConstraints();valuesColumn.setMinWidth(100);valuesColumn.setHgrow(Priority.ALWAYS);
        var typesColumn=new ColumnConstraints();typesColumn.setMinWidth(60);typesColumn.setPrefWidth(150);
        grid.getColumnConstraints().setAll(labels,valuesColumn,typesColumn);
        TextField name=new TextField(prepared.snapshot().title()),brand=new TextField(prepared.snapshot().brand());
        grid.addRow(0,new Label(tr("fbo.column.name")),name);grid.addRow(1,new Label(tr("znack.registration.brand")),brand);
        grid.addRow(2,new Label("TN VED"),new Label(prepared.tnved()));
        var permit=new ComboBox<GoodsDocument>();permit.getItems().setAll(prepared.documents().candidates());
        permit.setConverter(new StringConverter<>(){public String toString(GoodsDocument d){return d==null?"":d.type()+" · "+d.number()+" · "+d.date();}public GoodsDocument fromString(String ignored){throw new UnsupportedOperationException();}});
        if(prepared.documents().automatic()!=null)permit.setValue(prepared.documents().automatic());else if(permit.getItems().size()==1)permit.getSelectionModel().selectFirst();
        grid.addRow(3,new Label(tr("znack.registration.documents")),permit);
        Label documentNote=new Label(tr(prepared.documents().requiresReview()?"znack.registration.perproduct_review":"znack.registration.ozon_required"));documentNote.setWrapText(true);grid.add(documentNote,0,4,3,1);
        var inputs=new ArrayList<Input>();int row=5;
        for(Attribute attribute:prepared.required()) {
            if(Set.of(2478L,2504L,3959L,ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID).contains(attribute.id()))continue;
            if(attribute.id()==13933L&&prepared.preflight().categoryTnved().length()==10)continue;
            String suggested=prepared.suggestions().getOrDefault(attribute.id(),attribute.id()==13933L?prepared.tnved():"");
            Control value;
            if(attribute.presetOnly()) {var choice=new ComboBox<String>();choice.getItems().setAll(attribute.presets());if(choice.getItems().contains(suggested))choice.setValue(suggested);value=choice;}
            else value=new TextField(suggested);
            ComboBox<String> type=new ComboBox<>();type.getItems().setAll(attribute.valueTypes());if(type.getItems().size()==1)type.getSelectionModel().selectFirst();
            type.setPromptText(tr("znack.registration.ozon_attribute_type"));type.setVisible(!type.getItems().isEmpty());type.setManaged(!type.getItems().isEmpty());
            grid.addRow(row++,new Label(attribute.name()+" *"),value,type);inputs.add(new Input(attribute,value,type));
        }
        Label error=new Label();error.setWrapText(true);grid.add(error,0,row,3,1);
        for(var child:grid.getChildren()){if(child instanceof Label label){label.setWrapText(true);label.setMinWidth(0);}if(child instanceof Control control)control.setMaxWidth(Double.MAX_VALUE);}
        var scroll=new ScrollPane(grid);scroll.setFitToWidth(true);scroll.setMinWidth(0);scroll.setPrefViewportHeight(420);dialog.getDialogPane().setContent(scroll);dialog.getDialogPane().setPrefSize(760,570);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        ButtonType save=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(save,ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            boolean invalid=name.getText().isBlank()||brand.getText().isBlank()||permit.getValue()==null;
            for(var input:inputs) {
                String value=input.value() instanceof TextField text?text.getText():((ComboBox<?>)input.value()).getValue()==null?"":((ComboBox<?>)input.value()).getValue().toString();
                if(value.isBlank()||(!input.type().getItems().isEmpty()&&input.type().getValue()==null))invalid=true;
            }
            if(invalid){event.consume();error.setText(tr("znack.registration.ozon_required"));}
        });
        dialog.setResultConverter(button->{
            if(button!=save)return null;var values=new LinkedHashMap<Long,String>();var types=new LinkedHashMap<Long,String>();
            values.put(2478L,name.getText().strip());values.put(2504L,brand.getText().strip());
            for(var input:inputs) {
                String value=input.value() instanceof TextField text?text.getText():((ComboBox<?>)input.value()).getValue().toString();values.put(input.attribute().id(),value.strip());
                if(input.type().getValue()!=null)types.put(input.attribute().id(),input.type().getValue());
            }
            GoodsDocument document=permit.getValue();long id=document.type().toUpperCase(Locale.ROOT).contains("CERTIFICATE")?ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID:ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID;
            values.put(id,document.number()+":::"+document.date());
            return new Draft(prepared.tnved(),prepared.preflight().categoryTnved(),prepared.categoryId(),name.getText().strip(),brand.getText().strip(),values,types).withProductCategory(prepared.snapshot().metadata().get("source.category.name"));
        });
        return dialog.showAndWait().orElse(null);
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
