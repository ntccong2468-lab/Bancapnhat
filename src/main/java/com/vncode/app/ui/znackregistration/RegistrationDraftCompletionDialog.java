package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.shared.*;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.*;

/** Catalog-specific requirements are completed before the first GTIN allocation or feed submission. */
final class RegistrationDraftCompletionDialog {
    record Value(String text,String type) {}
    private static final Set<Long> OMITTED=Set.of(3959L,ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID);
    private RegistrationDraftCompletionDialog() {}

    static List<Attribute> missing(Draft draft,List<Attribute> schema,String wbSize) {
        var result=new LinkedHashMap<Long,Attribute>();
        for(var attribute:schema){
            if(OMITTED.contains(attribute.id())||attribute.id()==13933L&&draft.feedTnved().length()==10)continue;
            String value=value(draft,attribute.id());
            String type=draft.attributeTypes().getOrDefault(attribute.id(),ZnackWbAttributeMapper.resolveValueType(attribute,value,wbSize));
            if(value.isBlank()||attribute.presetOnly()&&!attribute.presets().contains(value)||type==null||!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(type))result.put(attribute.id(),attribute);
        }
        if(draft.goodName()==null||draft.goodName().isBlank())result.putIfAbsent(2478L,new Attribute(2478,"Наименование товара","string",false,false,true,false,List.of()));
        if(draft.brand()==null||draft.brand().isBlank())result.putIfAbsent(2504L,new Attribute(2504,"Товарный знак","string",false,false,true,false,List.of()));
        return List.copyOf(result.values());
    }
    static Draft complete(Draft draft,List<Attribute> schema,String wbSize,Map<Long,Value> entered) {
        var values=new LinkedHashMap<>(draft.attributes());var types=new LinkedHashMap<>(draft.attributeTypes());
        for(var attribute:missing(draft,schema,wbSize)){
            var input=entered.get(attribute.id());String text=input==null||input.text()==null?"":input.text().strip();
            if(text.isBlank()||text.length()>5000||attribute.presetOnly()&&!attribute.presets().contains(text))throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
            String type=input.type()==null?ZnackWbAttributeMapper.resolveValueType(attribute,text,wbSize):input.type();
            if(type==null||!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(type))throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
            values.put(attribute.id(),text);types.put(attribute.id(),type);
        }
        var result=new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),values.getOrDefault(2478L,draft.goodName()),values.getOrDefault(2504L,draft.brand()),values,types,draft.autoSign(),draft.isKit());
        if(!missing(result,schema,wbSize).isEmpty())throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
        return result;
    }
    static Draft show(Window owner,Draft draft,List<Attribute> schema,String wbSize) {
        var missing=missing(draft,schema,wbSize);if(missing.isEmpty())return draft;
        var dialog=new Dialog<Draft>();if(owner!=null)dialog.initOwner(owner);dialog.setTitle(tr("znack.registration.batch_preflight"));dialog.setHeaderText(tr("znack.registration.complete_missing_catalog_hint"));
        var entered=new LinkedHashMap<Long,Value>();
        for(var attribute:missing)entered.put(attribute.id(),new Value(value(draft,attribute.id()),draft.attributeTypes().getOrDefault(attribute.id(),ZnackWbAttributeMapper.resolveValueType(attribute,value(draft,attribute.id()),wbSize))));
        var table=new TableView<Attribute>();table.getItems().setAll(missing);table.setPrefHeight(320);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var name=new TableColumn<Attribute,String>(tr("znack.registration.complete_missing_field"));name.setCellValueFactory(row->new ReadOnlyStringWrapper(row.getValue().name()));RegistrationTextCells.wrap(name);name.setPrefWidth(210);
        var input=new TableColumn<Attribute,Attribute>(tr("znack.registration.complete_missing_value"));input.setCellValueFactory(row->new ReadOnlyObjectWrapper<>(row.getValue()));input.setPrefWidth(310);
        input.setCellFactory(c->new TableCell<>(){
            final ComboBox<String> box=new ComboBox<>();Attribute active;boolean rendering;
            {box.setId("registrationCatalogMissingValue");box.setMaxWidth(Double.MAX_VALUE);box.getEditor().textProperty().addListener((o,a,b)->{if(!rendering&&active!=null&&box.isEditable())save(b);});box.valueProperty().addListener((o,a,b)->{if(!rendering&&active!=null&&b!=null)save(b);});}
            private void save(String text){var old=entered.get(active.id());String type=old.type();if(type==null)type=ZnackWbAttributeMapper.resolveValueType(active,text,wbSize);entered.put(active.id(),new Value(text,type));}
            protected void updateItem(Attribute attribute,boolean empty){super.updateItem(attribute,empty);active=empty?null:attribute;if(active==null){setGraphic(null);return;}rendering=true;box.setEditable(!attribute.presetOnly());box.getItems().setAll(attribute.presets());box.setValue(entered.get(attribute.id()).text());box.getEditor().setText(entered.get(attribute.id()).text());rendering=false;setGraphic(box);}
        });
        var type=new TableColumn<Attribute,Attribute>(tr("znack.registration.ozon_attribute_type"));type.setCellValueFactory(row->new ReadOnlyObjectWrapper<>(row.getValue()));type.setPrefWidth(170);
        type.setCellFactory(c->new TableCell<>(){
            final ComboBox<String> box=new ComboBox<>();Attribute active;boolean rendering;
            {box.setId("registrationCatalogMissingType");box.setMaxWidth(Double.MAX_VALUE);box.valueProperty().addListener((o,a,b)->{if(!rendering&&active!=null&&b!=null)entered.put(active.id(),new Value(entered.get(active.id()).text(),b));});}
            protected void updateItem(Attribute attribute,boolean empty){super.updateItem(attribute,empty);active=empty?null:attribute;if(active==null||attribute.valueTypes().isEmpty()){setGraphic(null);return;}rendering=true;box.getItems().setAll(attribute.valueTypes());box.setValue(entered.get(attribute.id()).type());rendering=false;setGraphic(box);}
        });
        table.getColumns().setAll(name,input,type);var error=new Label();error.setWrapText(true);dialog.getDialogPane().setContent(new VBox(10,table,error));dialog.getDialogPane().setPrefWidth(800);dialog.getDialogPane().setId("registrationCatalogMissingDialog");
        var apply=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,new ButtonType(tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE));dialog.getDialogPane().lookupButton(apply).setId("registrationCatalogMissingApply");
        dialog.getDialogPane().lookupButton(apply).addEventFilter(javafx.event.ActionEvent.ACTION,event->{try{complete(draft,schema,wbSize,entered);}catch(IllegalArgumentException invalid){event.consume();error.setText(tr("znack.registration.ozon_required"));}});
        dialog.setResultConverter(button->button==apply?complete(draft,schema,wbSize,entered):null);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);return dialog.showAndWait().orElse(null);
    }
    private static String value(Draft draft,long id){return Objects.requireNonNullElse(id==2478L?draft.goodName():id==2504L?draft.brand():draft.attributes().get(id),"");}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
