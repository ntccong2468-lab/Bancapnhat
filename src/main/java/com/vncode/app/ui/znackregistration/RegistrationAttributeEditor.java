package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.RegistrationAttributeRules;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

/** Reuses controls so changing a conditional parent never discards entered child values. */
final class RegistrationAttributeEditor extends VBox {
    private List<Attribute> schema=List.of();
    private final Map<Long,TextField> inputs=new LinkedHashMap<>();
    private final Map<Long,ComboBox<String>> types=new LinkedHashMap<>();
    private final Map<Long,VBox> rows=new LinkedHashMap<>();
    private List<Long> active=List.of();
    RegistrationAttributeEditor(){super(8);}
    void clear(){schema=List.of();inputs.clear();types.clear();rows.clear();active=List.of();getChildren().clear();}
    void setSchema(List<Attribute> model,Map<Long,String> old,Map<Long,String> oldTypes){
        clear();schema=List.copyOf(model);var all=new LinkedHashMap<Long,Attribute>();var queue=new ArrayDeque<>(model);
        while(!queue.isEmpty()){var a=queue.removeFirst();if(all.putIfAbsent(a.id(),a)!=null)continue;a.dependencies().values().forEach(queue::addAll);}
        for(var a:all.values()){
            var input=new TextField(old.getOrDefault(a.id(),""));input.setId("znackV3Attribute-"+a.id());
            input.setPromptText(a.presets().isEmpty()?"Giá trị cần xác nhận":String.join(" / ",a.presets()));
            var type=new ComboBox<String>();type.getItems().setAll(a.valueTypes());type.setValue(oldTypes.get(a.id()));
            var fields=new HBox(8,input,type);HBox.setHgrow(input,Priority.ALWAYS);
            inputs.put(a.id(),input);types.put(a.id(),type);rows.put(a.id(),new VBox(4,new Label("Thuộc tính "+a.id()+" — "+a.name()),fields));
            input.textProperty().addListener((o,before,after)->refresh());
        }refresh();
    }
    private void refresh(){
        var entered=new LinkedHashMap<Long,String>();inputs.forEach((id,input)->entered.put(id,input.getText().strip()));
        var selected=RegistrationAttributeRules.required(schema,entered).stream().map(Attribute::id).toList();
        if(!selected.equals(active)){active=selected;getChildren().setAll(active.stream().map(rows::get).toList());}
    }
    Map<Long,String> values(){var out=new LinkedHashMap<Long,String>();for(var id:active)out.put(id,inputs.get(id).getText().strip());return out;}
    Set<Long> managedIds(){return Set.copyOf(inputs.keySet());}
    void suggest(Map<Long,String> values,Map<Long,String> proposedTypes){
        values.forEach((id,value)->{var input=inputs.get(id);if(input!=null&&input.getText().isBlank()){input.setText(value);if(types.get(id).getValue()==null)types.get(id).setValue(proposedTypes.get(id));}});
    }
    Map<Long,String> types(){var out=new LinkedHashMap<Long,String>();for(var id:active)if(types.get(id).getValue()!=null)out.put(id,types.get(id).getValue());return out;}
}
