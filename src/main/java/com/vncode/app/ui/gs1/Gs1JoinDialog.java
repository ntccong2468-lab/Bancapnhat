package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.shared.*;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/** Structured editor for the official GS1 form; unknown server fields survive edits. */
final class Gs1JoinDialog {
    @FunctionalInterface interface AddressSearch {List<NationalCatalogGs1Client.Address> search(String query)throws IOException;}
    private final Gs1Pane owner;
    private final String inn;
    private final JsonObject form;
    private final Map<String,List<NationalCatalogGs1Client.Choice>> dictionaries;
    private final AddressSearch addresses;
    private final BiConsumer<JsonObject,Boolean> save;
    private final Dialog<Void> dialog=new Dialog<>();
    private final TabPane steps=new TabPane();
    private final Label errors=new Label();
    private final List<TextField> required=new ArrayList<>();
    private final TextArea review=new TextArea();
    Gs1JoinDialog(Gs1Pane owner,String inn,JsonObject original,Map<String,List<NationalCatalogGs1Client.Choice>> dictionaries,AddressSearch addresses,BiConsumer<JsonObject,Boolean> save){
        this.owner=owner;this.inn=inn;this.form=original.deepCopy();this.dictionaries=dictionaries;this.addresses=addresses;this.save=save;
        AlertService.applyTheme(dialog);if(owner.getScene()!=null)dialog.initOwner(owner.getScene().getWindow());dialog.setTitle(tr("gs1.join"));dialog.getDialogPane().setPrefSize(850,640);
        JsonObject applicant=section(form,"applicant");applicant.addProperty("inn",inn);
        VBox enterprise=new VBox(10);enterprise.getChildren().add(new Label(tr("gs1.form_notice")));
        for(String field:List.of("companyName","companyNameShort","countryId","firstName","lastName","email","phone","ogrn","kpp","okved2","companyNameEN"))enterprise.getChildren().add(text(applicant,field,!Set.of("companyNameEN","kpp").contains(field)));
        Label identity=new Label("INN: "+inn);enterprise.getChildren().add(identity);
        enterprise.getChildren().add(choice(applicant,"opf","opf"));enterprise.getChildren().add(choice(applicant,"position","positions"));
        CheckBox marking=new CheckBox(tr("gs1.field.isMarked"));marking.setSelected(applicant.has("isMarked")&&applicant.get("isMarked").isJsonPrimitive()&&applicant.get("isMarked").getAsBoolean());marking.selectedProperty().addListener((o,a,b)->applicant.addProperty("isMarked",b));applicant.addProperty("isMarked",marking.isSelected());enterprise.getChildren().add(marking);tab("gs1.enterprise",enterprise);
        JsonObject bank=section(form,"bankDetails");VBox banking=new VBox(10);
        for(String field:List.of("name","bik","settlementAccount","correspondentAccount"))banking.getChildren().add(text(bank,field,!field.equals("correspondentAccount")));
        CheckBox noCorrespondent=new CheckBox(tr("gs1.field.noCorrespondentAccount"));noCorrespondent.setSelected(bank.has("noCorrespondentAccount")&&bank.get("noCorrespondentAccount").getAsBoolean());noCorrespondent.selectedProperty().addListener((o,a,b)->bank.addProperty("noCorrespondentAccount",b));banking.getChildren().add(noCorrespondent);tab("gs1.bank",banking);
        JsonObject addressSection=section(form,"addresses");VBox addressFields=new VBox(18,address(section(addressSection,"legalAddress"),"gs1.legal_address"),address(section(addressSection,"physicalAddress"),"gs1.physical_address"));
        if(addressSection.has("productionAddresses")&&addressSection.get("productionAddresses").isJsonArray())for(JsonElement item:addressSection.getAsJsonArray("productionAddresses"))if(item.isJsonObject())addressFields.getChildren().add(address(item.getAsJsonObject(),"gs1.production_address"));tab("gs1.addresses",addressFields);
        JsonObject company=section(form,"companyDetails");VBox business=new VBox(12);
        business.getChildren().addAll(choice(company,"enterpriseSector","enterprise-sector"),choice(company,"mainProductGCP","main-products"),choice(company,"obtainingDocumentsMethod","documents"),choice(company,"provider","provider"));
        ComboBox<NationalCatalogGs1Client.Choice> count=new ComboBox<>();count.getItems().setAll(new NationalCatalogGs1Client.Choice("less1000",tr("gs1.less_1000")),new NationalCatalogGs1Client.Choice("more1000",tr("gs1.more_1000")));count.getItems().stream().filter(c->c.id().equals(text(company,"productsCountToMark"))).findFirst().ifPresent(count::setValue);count.valueProperty().addListener((o,a,b)->{if(b!=null)company.addProperty("productsCountToMark",b.id());});business.getChildren().add(labeled("gs1.field.productsCountToMark",count));
        business.getChildren().add(idChecklist(company,"accountType","profiles-organization"));business.getChildren().add(objectChecklist(company,"partisipationInGovernmentProjects","participation"));
        for(String[] group:new String[][]{{"implementationMethods","selling-ways"},{"distributionChannels","distribution-channels"}}){JsonObject data=section(company,group[0]);business.getChildren().add(objectChecklist(data,"fields",group[1]));business.getChildren().add(text(data,"moreText",false));}tab("gs1.business",business);
        JsonArray contacts=form.has("contacts")&&form.get("contacts").isJsonArray()?form.getAsJsonArray("contacts"):new JsonArray();form.add("contacts",contacts);if(contacts.isEmpty())contacts.add(new JsonObject());VBox contactFields=new VBox(16);for(JsonElement item:contacts)if(item.isJsonObject())contactFields.getChildren().add(contact(item.getAsJsonObject()));
        Button add=new Button(tr("gs1.add_contact"));add.setOnAction(e->{JsonObject value=new JsonObject();contacts.add(value);contactFields.getChildren().add(contact(value));});VBox contactsBox=new VBox(12,contactFields,add);tab("gs1.contacts",contactsBox);
        review.setWrapText(true);review.setEditable(false);review.setPrefRowCount(18);tab("gs1.review",new VBox(10,new Label(tr("gs1.submit_notice")),review));
        steps.getSelectionModel().selectedIndexProperty().addListener((o,a,b)->refreshReview());errors.setWrapText(true);errors.getStyleClass().add("text-danger");
        dialog.getDialogPane().setContent(new VBox(12,steps,errors));VBox.setVgrow(steps,Priority.ALWAYS);
        ButtonType saveDraft=new ButtonType(tr("gs1.save_draft"),ButtonBar.ButtonData.OTHER),submit=new ButtonType(tr("gs1.sign_submit"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(saveDraft,submit,ButtonType.CANCEL);
        for(ButtonType type:List.of(saveDraft,submit)){
            Button button=(Button)dialog.getDialogPane().lookupButton(type);button.disableProperty().bind(owner.busyProperty());button.addEventFilter(javafx.event.ActionEvent.ACTION,event->{
                event.consume();boolean signing=type==submit;
                if(signing&&!valid()){errors.setText(tr("gs1.invalid_fields"));return;}
                refreshReview();save.accept(form.deepCopy(),signing);
            });
        }
    }
    void show(){refreshReview();dialog.showAndWait();}
    private Node contact(JsonObject contact){VBox fields=new VBox(10);for(String key:List.of("lastName","firstName","email","phone"))fields.getChildren().add(text(contact,key,true));fields.getChildren().add(choice(contact,"position","positions"));return fields;}
    private Node address(JsonObject data,String title){
        VBox box=new VBox(10,new Label(tr(title)));box.getChildren().add(text(data,"countryId",true));
        TextField location=new TextField(text(data,"fullAddress"));if(location.getText().isBlank())location.getStyleClass().add("required-field");required.add(location);
        location.textProperty().addListener((o,a,b)->{data.addProperty("fullAddress",b);data.remove("fullGuid");data.remove("houseGuid");});
        Button lookup=new Button(tr("gs1.find_address"));Label result=new Label();result.setWrapText(true);
        lookup.setOnAction(e->{String query=location.getText().strip();if(query.length()<3){result.setText(tr("gs1.invalid_fields"));return;}lookup.setDisable(true);Task<List<NationalCatalogGs1Client.Address>> task=new Task<>(){@Override protected List<NationalCatalogGs1Client.Address> call()throws Exception{return addresses.search(query);}};
            task.setOnSucceeded(done->{lookup.setDisable(false);if(!dialog.isShowing())return;ChoiceDialog<NationalCatalogGs1Client.Address> choice=new ChoiceDialog<>(null,task.getValue());AlertService.applyTheme(choice);choice.initOwner(dialog.getDialogPane().getScene().getWindow());choice.setTitle(tr("gs1.find_address"));choice.showAndWait().ifPresent(value->{location.setText(value.address());data.addProperty("fullGuid",value.fullGuid());data.addProperty("houseGuid",value.houseGuid());data.addProperty("postalCode",value.postalCode());result.setText(value.address());});});
            task.setOnFailed(done->{lookup.setDisable(false);result.setText(tr("gs1.error"));});AppTaskExecutor.execute(task);
        });box.getChildren().addAll(labeled("gs1.field.fullAddress",location),lookup,result);return box;
    }
    private Node text(JsonObject data,String key,boolean mandatory){TextField input=new TextField(text(data,key));if(mandatory)required.add(input);input.textProperty().addListener((o,a,b)->data.addProperty(key,b.strip()));return labeled("gs1.field."+key,input);}
    private Node choice(JsonObject data,String key,String dictionary){
        ComboBox<NationalCatalogGs1Client.Choice> input=new ComboBox<>();input.getItems().setAll(dictionaries.getOrDefault(dictionary,List.of()));input.setMaxWidth(Double.MAX_VALUE);input.getItems().stream().filter(c->c.id().equals(text(data,key))).findFirst().ifPresent(input::setValue);input.valueProperty().addListener((o,a,b)->{if(b!=null)data.addProperty(key,b.id());});return labeled("gs1.field."+key,input);
    }
    private Node idChecklist(JsonObject data,String key,String dictionary){
        JsonArray ids=data.has(key)&&data.get(key).isJsonArray()?data.getAsJsonArray(key):new JsonArray();data.add(key,ids);VBox group=new VBox(6,new Label(tr("gs1.field."+key)));Set<String> selected=new LinkedHashSet<>();ids.forEach(id->{if(id.isJsonPrimitive())selected.add(id.getAsString());});
        for(var choice:dictionaries.getOrDefault(dictionary,List.of())){CheckBox box=new CheckBox(choice.text());box.setSelected(selected.contains(choice.id()));box.selectedProperty().addListener((o,a,b)->{if(b)selected.add(choice.id());else selected.remove(choice.id());JsonArray values=new JsonArray();selected.forEach(values::add);data.add(key,values);});group.getChildren().add(box);}return group;
    }
    private Node objectChecklist(JsonObject data,String key,String dictionary){
        JsonArray fields=data.has(key)&&data.get(key).isJsonArray()?data.getAsJsonArray(key):new JsonArray();data.add(key,fields);
        if(fields.isEmpty())for(var choice:dictionaries.getOrDefault(dictionary,List.of())){JsonObject value=new JsonObject();value.addProperty("id",choice.id());value.addProperty("text",choice.text());value.addProperty("checked",false);fields.add(value);}
        VBox group=new VBox(6,new Label(tr("gs1.field."+dictionary)));
        for(JsonElement item:fields)if(item.isJsonObject()){JsonObject value=item.getAsJsonObject();CheckBox box=new CheckBox(text(value,"text"));box.setSelected(value.has("checked")&&value.get("checked").getAsBoolean());box.selectedProperty().addListener((o,a,b)->value.addProperty("checked",b));group.getChildren().add(box);}return group;
    }
    private boolean valid(){
        if(required.stream().anyMatch(f->f.getText().isBlank()))return false;
        JsonObject a=section(form,"applicant"),b=section(form,"bankDetails"),company=section(form,"companyDetails");
        if(!inn.equals(text(a,"inn"))||!text(a,"email").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||!text(a,"ogrn").matches("[0-9]{13}|[0-9]{15}"))return false;
        if(inn.length()==10&&!text(a,"kpp").matches("[0-9]{4}[A-Z0-9]{2}[0-9]{3}"))return false;
        if(!text(b,"bik").matches("[0-9]{9}")||!text(b,"settlementAccount").matches("[0-9]{20}"))return false;
        if(!b.has("noCorrespondentAccount")||!b.get("noCorrespondentAccount").getAsBoolean())if(!text(b,"correspondentAccount").matches("[0-9]{20}"))return false;
        for(String key:List.of("enterpriseSector","mainProductGCP","obtainingDocumentsMethod","productsCountToMark"))if(text(company,key).isBlank())return false;
        if("WORKFLOW_EL".equals(text(company,"obtainingDocumentsMethod"))&&text(company,"provider").isBlank())return false;
        for(String key:List.of("legalAddress","physicalAddress")){JsonObject address=section(section(form,"addresses"),key);if("RU".equals(text(address,"countryId"))&&text(address,"fullGuid").isBlank())return false;}
        return true;
    }
    private void refreshReview(){StringBuilder text=new StringBuilder();describe(form,text,0);review.setText(text.toString());}
    private void describe(JsonElement value,StringBuilder target,int indent){
        if(value.isJsonObject())for(var field:value.getAsJsonObject().entrySet()){
            if(Set.of("isSent","errorText","expiryDate").contains(field.getKey()))continue;
            target.append("  ".repeat(indent)).append(tr("gs1.field."+field.getKey())).append(": ");
            if(field.getValue().isJsonPrimitive())target.append(field.getValue().getAsString()).append('\n');else{target.append('\n');describe(field.getValue(),target,indent+1);}
        }else if(value.isJsonArray())value.getAsJsonArray().forEach(item->describe(item,target,indent));
    }
    private void tab(String key,VBox content){content.setPadding(new Insets(14));ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);Tab tab=new Tab(tr(key),scroll);tab.setClosable(false);steps.getTabs().add(tab);}
    private Node labeled(String key,Node node){VBox row=new VBox(5,new Label(tr(key)),node);VBox.setVgrow(node,Priority.NEVER);return row;}
    private static JsonObject section(JsonObject parent,String key){if(!parent.has(key)||!parent.get(key).isJsonObject())parent.add(key,new JsonObject());return parent.getAsJsonObject(key);}
    private static String text(JsonObject data,String key){return NationalCatalogGs1Client.text(data,key);}
    private static String tr(String key){String label=I18nService.getInstance().tr(key);return label.equals(key)?key.substring(key.lastIndexOf('.')+1):label;}
}
