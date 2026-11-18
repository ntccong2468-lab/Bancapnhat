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
    static final class InvalidChecklistException extends IllegalArgumentException {
        InvalidChecklistException(String dictionary,IllegalArgumentException cause){
            super(tr("gs1.checklist.invalid").replace("{0}",tr("gs1.field."+dictionary))+" ["+cause.getMessage()+"]",cause);
        }
    }
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
    private record FieldBinding(JsonObject data,String key,Control control) {}
    private final List<FieldBinding> fields=new ArrayList<>();
    private final TextArea review=new TextArea();
    private final javafx.beans.property.IntegerProperty contactCount=new javafx.beans.property.SimpleIntegerProperty();
    Gs1JoinDialog(Gs1Pane owner,String inn,JsonObject original,Map<String,List<NationalCatalogGs1Client.Choice>> dictionaries,AddressSearch addresses,BiConsumer<JsonObject,Boolean> save){
        this.owner=owner;this.inn=inn;this.form=original.deepCopy();this.dictionaries=dictionaries;this.addresses=addresses;this.save=save;
        AlertService.applyTheme(dialog);if(owner.getScene()!=null)dialog.initOwner(owner.getScene().getWindow());Gs1Dialogs.centerOnOwner(dialog);dialog.setTitle(tr("gs1.join"));dialog.getDialogPane().setPrefSize(850,640);steps.getStyleClass().add("workspace-tab-strip");steps.disableProperty().bind(owner.busyProperty());
        JsonObject applicant=section(form,"applicant");applicant.addProperty("inn",inn);
        VBox enterprise=new VBox(10);enterprise.getChildren().add(new Label(tr("gs1.form_notice")));
        for(String field:List.of("companyName","companyNameShort","countryId","firstName","lastName","middleName","email","phone","ogrn","kpp","okved2","companyNameEN","tradeMark","url"))enterprise.getChildren().add(text(applicant,field,!Set.of("companyNameEN","kpp","middleName","tradeMark","url").contains(field)));
        Label identity=new Label("INN: "+inn);enterprise.getChildren().add(identity);
        enterprise.getChildren().add(choice(applicant,"opf","opf"));enterprise.getChildren().add(choice(applicant,"position","positions"));
        CheckBox marking=new CheckBox(tr("gs1.field.isMarked"));marking.setId("gs1ApplicantMarked");
        boolean markingKnown=applicant.has("isMarked")&&applicant.get("isMarked").isJsonPrimitive()&&applicant.get("isMarked").getAsJsonPrimitive().isBoolean();
        marking.setAllowIndeterminate(true);marking.setSelected(markingKnown&&applicant.get("isMarked").getAsBoolean());marking.setIndeterminate(!markingKnown);fields.add(new FieldBinding(applicant,"isMarked",marking));
        Runnable updateMarking=()->{if(marking.isIndeterminate())applicant.remove("isMarked");else applicant.addProperty("isMarked",marking.isSelected());};
        marking.selectedProperty().addListener((o,a,b)->updateMarking.run());marking.indeterminateProperty().addListener((o,a,b)->updateMarking.run());enterprise.getChildren().add(marking);tab("gs1.enterprise",enterprise);
        JsonObject bank=section(form,"bankDetails");VBox banking=new VBox(10);
        for(String field:List.of("name","bik","settlementAccount","correspondentAccount"))banking.getChildren().add(text(bank,field,!field.equals("correspondentAccount")));
        CheckBox noCorrespondent=new CheckBox(tr("gs1.field.noCorrespondentAccount"));noCorrespondent.setSelected(bank.has("noCorrespondentAccount")&&bank.get("noCorrespondentAccount").getAsBoolean());noCorrespondent.selectedProperty().addListener((o,a,b)->bank.addProperty("noCorrespondentAccount",b));banking.getChildren().add(noCorrespondent);tab("gs1.bank",banking);
        JsonObject addressSection=section(form,"addresses"),legal=section(addressSection,"legalAddress"),physical=section(addressSection,"physicalAddress");
        VBox physicalEditor=new VBox(10,address(physical,"gs1.physical_address"));
        Button copyLegal=new Button(tr("gs1.copy_legal_address"));copyLegal.setId("gs1CopyLegalAddress");copyLegal.setOnAction(e->{
            copyAddress(legal,physical);fields.removeIf(f->f.data()==physical);physicalEditor.getChildren().setAll(address(physical,"gs1.physical_address"));
        });
        JsonArray production=addressSection.has("productionAddresses")&&addressSection.get("productionAddresses").isJsonArray()?addressSection.getAsJsonArray("productionAddresses"):new JsonArray();if(!addressSection.has("productionAddresses")||addressSection.get("productionAddresses").isJsonNull())addressSection.add("productionAddresses",production);
        VBox productionEditors=new VBox(14);for(JsonElement item:production)if(item.isJsonObject())addProductionEditor(productionEditors,production,item.getAsJsonObject());
        Button addProduction=new Button(tr("gs1.add_production_address"));addProduction.setId("gs1AddProductionAddress");addProduction.setDisable(!addressSection.get("productionAddresses").isJsonArray());addProduction.setOnAction(e->{JsonObject value=new JsonObject();if(legal.has("countryId"))value.add("countryId",legal.get("countryId").deepCopy());production.add(value);addProductionEditor(productionEditors,production,value);});
        VBox addressFields=new VBox(18,address(legal,"gs1.legal_address"),copyLegal,physicalEditor,productionEditors,addProduction);tab("gs1.addresses",addressFields);
        JsonObject company=section(form,"companyDetails");VBox business=new VBox(12);
        business.getChildren().addAll(choice(company,"enterpriseSector","enterprise-sector"),choice(company,"mainProductGCP","main-products"),choice(company,"obtainingDocumentsMethod","documents"),choice(company,"provider","provider"));
        ComboBox<NationalCatalogGs1Client.Choice> count=new ComboBox<>();count.getItems().setAll(new NationalCatalogGs1Client.Choice("less1000",tr("gs1.less_1000")),new NationalCatalogGs1Client.Choice("more1000",tr("gs1.more_1000")));count.getItems().stream().filter(c->c.id().equals(text(company,"productsCountToMark"))).findFirst().ifPresent(count::setValue);count.valueProperty().addListener((o,a,b)->{if(b!=null)company.addProperty("productsCountToMark",b.id());});business.getChildren().add(labeled("gs1.field.productsCountToMark",count));
        business.getChildren().add(idChecklist(company,"accountType","profiles-organization"));business.getChildren().add(objectChecklist(company,"partisipationInGovernmentProjects","participation"));
        for(String[] group:new String[][]{{"implementationMethods","selling-ways"},{"distributionChannels","distribution-channels"}}){JsonObject data=section(company,group[0]);business.getChildren().add(objectChecklist(data,"fields",group[1]));business.getChildren().add(text(data,"moreText",false));}tab("gs1.business",business);
        JsonArray contacts=form.has("contacts")&&form.get("contacts").isJsonArray()?form.getAsJsonArray("contacts"):new JsonArray();form.add("contacts",contacts);if(contacts.isEmpty())contacts.add(new JsonObject());contactCount.set(contacts.size());VBox contactFields=new VBox(16);for(JsonElement item:contacts)if(item.isJsonObject())contactFields.getChildren().add(contact(item.getAsJsonObject()));
        Button add=new Button(tr("gs1.add_contact"));add.setOnAction(e->{JsonObject value=new JsonObject();contacts.add(value);contactCount.set(contacts.size());contactFields.getChildren().add(contact(value));});VBox contactsBox=new VBox(12,contactFields,add);tab("gs1.contacts",contactsBox);
        review.setWrapText(true);review.setEditable(false);review.setPrefRowCount(18);tab("gs1.review",new VBox(10,new Label(tr("gs1.submit_notice")),review));
        steps.getSelectionModel().selectedIndexProperty().addListener((o,a,b)->{errors.setText("");refreshReview();});errors.setWrapText(true);errors.getStyleClass().add("text-danger");errors.managedProperty().bind(errors.textProperty().isNotEmpty());errors.visibleProperty().bind(errors.managedProperty());
        steps.setMinHeight(0);steps.setPrefHeight(400);VBox.setVgrow(steps,Priority.ALWAYS);
        var body=new VBox(12,steps,errors);body.setMinHeight(0);
        // Keep the tab strip in view. Each step already owns its scroll viewport;
        // an additional whole-form scroll can move all fields into a blank tail.
        body.prefHeightProperty().bind(javafx.beans.binding.Bindings.max(120,dialog.getDialogPane().maxHeightProperty().subtract(160)));
        dialog.getDialogPane().setContent(body);
        ButtonType back=new ButtonType(tr("gs1.wizard.back"),ButtonBar.ButtonData.BACK_PREVIOUS),next=new ButtonType(tr("gs1.wizard.next"),ButtonBar.ButtonData.NEXT_FORWARD);
        ButtonType saveDraft=new ButtonType(tr("gs1.save_draft"),ButtonBar.ButtonData.OTHER),submit=new ButtonType(tr("gs1.sign_submit"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(back,next,saveDraft,submit,new ButtonType(tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE));
        Button backButton=(Button)dialog.getDialogPane().lookupButton(back),nextButton=(Button)dialog.getDialogPane().lookupButton(next);
        backButton.setId("gs1JoinBack");nextButton.setId("gs1JoinNext");
        backButton.disableProperty().bind(owner.busyProperty().or(steps.getSelectionModel().selectedIndexProperty().isEqualTo(0)));
        nextButton.disableProperty().bind(owner.busyProperty().or(steps.getSelectionModel().selectedIndexProperty().isEqualTo(steps.getTabs().size()-1)));
        backButton.addEventFilter(javafx.event.ActionEvent.ACTION,event->{event.consume();steps.getSelectionModel().selectPrevious();});
        nextButton.addEventFilter(javafx.event.ActionEvent.ACTION,event->{event.consume();var failure=Gs1JoinFormValidation.step(form,inn,steps.getSelectionModel().getSelectedIndex());if(failure.isPresent())showFailure(failure.get());else steps.getSelectionModel().selectNext();});
        for(ButtonType type:List.of(saveDraft,submit)){
            Button button=(Button)dialog.getDialogPane().lookupButton(type);button.disableProperty().bind(type==submit?owner.busyProperty().or(steps.getSelectionModel().selectedIndexProperty().isNotEqualTo(steps.getTabs().size()-1)):owner.busyProperty());button.addEventFilter(javafx.event.ActionEvent.ACTION,event->{
                event.consume();boolean signing=type==submit;
                if(signing){var failure=Gs1JoinFormValidation.first(form,inn);if(failure.isPresent()){showFailure(failure.get());return;}}
                refreshReview();save.accept(form.deepCopy(),signing);
            });
        }
    }
    void show(){refreshReview();dialog.showAndWait();}
    private Node contact(JsonObject contact){
        VBox editor=new VBox(10),row=new VBox(10);renderContact(editor,contact);
        Button copy=new Button(tr("gs1.copy_applicant_contact"));copy.setOnAction(e->{
            JsonObject applicant=section(form,"applicant");for(String key:List.of("lastName","firstName","middleName","position","email","phone")){contact.remove(key);if(applicant.has(key))contact.add(key,applicant.get(key).deepCopy());}
            fields.removeIf(field->field.data()==contact);renderContact(editor,contact);
        });
        Button remove=new Button(tr("gs1.remove_contact"));remove.disableProperty().bind(owner.busyProperty().or(contactCount.lessThanOrEqualTo(1)));
        remove.setOnAction(e->{var contacts=form.getAsJsonArray("contacts");if(contacts.size()<=1)return;
            for(int index=0;index<contacts.size();index++)if(contacts.get(index)==contact){contacts.remove(index);break;}
            contactCount.set(contacts.size());fields.removeIf(field->field.data()==contact);if(row.getParent() instanceof VBox parent)parent.getChildren().remove(row);
        });row.getChildren().setAll(editor,new HBox(10,copy,remove));return row;
    }
    private void renderContact(VBox editor,JsonObject contact){
        editor.getChildren().clear();for(String key:List.of("lastName","firstName","middleName","email","phone"))editor.getChildren().add(text(contact,key,!key.equals("middleName")));
        editor.getChildren().add(choice(contact,"position","positions"));
    }
    private Node address(JsonObject data,String title){
        VBox box=new VBox(10,new Label(tr(title)));box.getChildren().add(text(data,"countryId",true));
        var country=(TextField)fields.getLast().control();var revision=new java.util.concurrent.atomic.AtomicLong();
        country.textProperty().addListener((o,a,b)->{revision.incrementAndGet();data.remove("fullGuid");data.remove("houseGuid");data.remove("postalCode");});
        TextField location=new TextField(text(data,"fullAddress"));if(location.getText().isBlank())location.getStyleClass().add("required-field");fields.add(new FieldBinding(data,"fullAddress",location));
        location.textProperty().addListener((o,a,b)->{revision.incrementAndGet();data.addProperty("fullAddress",b);data.remove("fullGuid");data.remove("houseGuid");data.remove("postalCode");});
        Button lookup=new Button(tr("gs1.find_address"));Label result=new Label();result.setWrapText(true);
        lookup.setOnAction(e->{String query=location.getText().strip();if(query.length()<3){result.setText(tr("gs1.invalid_fields"));return;}lookup.setDisable(true);long selectedRevision=revision.get();Task<List<NationalCatalogGs1Client.Address>> task=new Task<>(){@Override protected List<NationalCatalogGs1Client.Address> call()throws Exception{return addresses.search(query);}};
            task.setOnSucceeded(done->{lookup.setDisable(false);if(!currentAddress(location,query,selectedRevision,revision))return;ChoiceDialog<NationalCatalogGs1Client.Address> choice=new ChoiceDialog<>(null,task.getValue());AlertService.applyTheme(choice);choice.initOwner(dialog.getDialogPane().getScene().getWindow());choice.setTitle(tr("gs1.find_address"));choice.showAndWait().ifPresent(value->{if(!currentAddress(location,query,selectedRevision,revision))return;location.setText(value.address());data.addProperty("fullGuid",value.fullGuid());data.addProperty("houseGuid",value.houseGuid());data.addProperty("postalCode",value.postalCode());result.setText(value.address());});});
            java.util.function.Consumer<Throwable> taskFailure = rejection ->{lookup.setDisable(false);result.setText(tr("gs1.error"));};
        task.setOnFailed(done -> taskFailure.accept(task.getException()));AppTaskExecutor.execute(task, taskFailure);
        });box.getChildren().addAll(labeled("gs1.field.fullAddress",location),lookup,result);return box;
    }
    private boolean currentAddress(TextField location,String query,long selectedRevision,java.util.concurrent.atomic.AtomicLong revision){
        return dialog.isShowing()&&selectedRevision==revision.get()&&query.equals(location.getText().strip())
                &&fields.stream().anyMatch(binding->binding.control()==location);
    }
    private Node text(JsonObject data,String key,boolean mandatory){TextField input=new TextField(text(data,key));fields.add(new FieldBinding(data,key,input));if(mandatory&&input.getText().isBlank())input.getStyleClass().add("required-field");input.textProperty().addListener((o,a,b)->data.addProperty(key,b.strip()));return labeled("gs1.field."+key,input);}
    private Node choice(JsonObject data,String key,String dictionary){
        ComboBox<NationalCatalogGs1Client.Choice> input=new ComboBox<>();fields.add(new FieldBinding(data,key,input));input.getItems().setAll(dictionaries.getOrDefault(dictionary,List.of()));input.setMaxWidth(Double.MAX_VALUE);String current=text(data,key);var selected=input.getItems().stream().filter(c->c.id().equals(current)).findFirst();
        if(selected.isPresent())input.setValue(selected.get());else if(!current.isBlank()){var retained=new NationalCatalogGs1Client.Choice(current,tr("gs1.current_catalog_value")+": "+current);input.getItems().add(retained);input.setValue(retained);}input.valueProperty().addListener((o,a,b)->{if(b!=null)data.addProperty(key,b.id());});return labeled("gs1.field."+key,input);
    }
    private Node idChecklist(JsonObject data,String key,String dictionary){
        JsonArray ids=data.has(key)&&data.get(key).isJsonArray()?data.getAsJsonArray(key):new JsonArray();data.add(key,ids);VBox group=new VBox(6,new Label(tr("gs1.field."+key)));Set<String> selected=new LinkedHashSet<>();ids.forEach(id->{if(id.isJsonPrimitive())selected.add(id.getAsString());});
        for(var choice:dictionaries.getOrDefault(dictionary,List.of())){CheckBox box=new CheckBox(choice.text());box.setSelected(selected.contains(choice.id()));box.selectedProperty().addListener((o,a,b)->{if(b)selected.add(choice.id());else selected.remove(choice.id());JsonArray values=new JsonArray();selected.forEach(values::add);data.add(key,values);});group.getChildren().add(box);}return group;
    }
    private Node objectChecklist(JsonObject data,String key,String dictionary){
        if(data.has(key)&&!data.get(key).isJsonNull()&&!data.get(key).isJsonArray())throw new InvalidChecklistException(dictionary,new IllegalArgumentException("GS1_CHECKLIST_INVALID"));
        JsonArray fields;
        try{fields=Gs1JoinChecklist.normalize(data.has(key)&&data.get(key).isJsonArray()?data.getAsJsonArray(key):new JsonArray(),dictionaries.getOrDefault(dictionary,List.of()));}
        catch(IllegalArgumentException invalid){throw new InvalidChecklistException(dictionary,invalid);}
        data.add(key,fields);
        VBox group=new VBox(6,new Label(tr("gs1.field."+dictionary)));
        for(JsonElement item:fields)if(item.isJsonObject()){JsonObject value=item.getAsJsonObject();CheckBox box=new CheckBox(Gs1JoinChecklist.label(value));
            box.setSelected(value.has("checked")&&value.get("checked").isJsonPrimitive()&&value.get("checked").getAsJsonPrimitive().isBoolean()&&value.get("checked").getAsBoolean());
            box.selectedProperty().addListener((o,a,b)->value.addProperty("checked",b));group.getChildren().add(box);}return group;
    }
    private boolean valid(){return Gs1JoinFormValidation.first(form,inn).isEmpty();}
    private void showFailure(Gs1JoinFormValidation.Failure failure){
        steps.getSelectionModel().select(failure.step());errors.setText(tr("gs1.invalid_fields")+" · "+tr("gs1.field."+failure.field()));
        fields.stream().filter(f->f.data()==failure.data()&&f.key().equals(failure.field())).findFirst().ifPresent(f->{
            if(!f.control().getStyleClass().contains("required-field"))f.control().getStyleClass().add("required-field");f.control().requestFocus();
        });
    }
    private void addProductionEditor(VBox container,JsonArray values,JsonObject data){
        VBox row=new VBox(8,address(data,"gs1.production_address"));Button remove=new Button(tr("gs1.remove_production_address"));remove.setOnAction(e->{
            for(int index=0;index<values.size();index++)if(values.get(index)==data){values.remove(index);break;}
            fields.removeIf(f->f.data()==data);container.getChildren().remove(row);
        });row.getChildren().add(remove);container.getChildren().add(row);
    }
    private static void copyAddress(JsonObject source,JsonObject target){
        for(String field:List.of("countryId","fullAddress","fullGuid","houseGuid","postalCode")){target.remove(field);if(source.has(field))target.add(field,source.get(field).deepCopy());}
    }
    private void refreshReview(){StringBuilder text=new StringBuilder();describe(form,text,0);review.setText(text.toString());}
    private void describe(JsonElement value,StringBuilder target,int indent){
        if(value.isJsonObject())for(var field:value.getAsJsonObject().entrySet()){
            if(Set.of("isSent","errorText","expiryDate").contains(field.getKey()))continue;
            target.append("  ".repeat(indent)).append(tr("gs1.field."+field.getKey())).append(": ");
            if(field.getValue().isJsonPrimitive())target.append(field.getValue().getAsString()).append('\n');else{target.append('\n');describe(field.getValue(),target,indent+1);}
        }else if(value.isJsonArray())value.getAsJsonArray().forEach(item->describe(item,target,indent));
    }
    private void tab(String key,VBox content){content.getStyleClass().add("workspace-form");content.setPadding(new Insets(14));ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);Tab tab=new Tab(tr(key),scroll);tab.setClosable(false);steps.getTabs().add(tab);}
    private Node labeled(String key,Node node){VBox row=new VBox(5,new Label(tr(key)),node);VBox.setVgrow(node,Priority.NEVER);return row;}
    private static JsonObject section(JsonObject parent,String key){if(!parent.has(key)||!parent.get(key).isJsonObject())parent.add(key,new JsonObject());return parent.getAsJsonObject(key);}
    private static String text(JsonObject data,String key){return NationalCatalogGs1Client.text(data,key);}
    private static String tr(String key){String label=I18nService.getInstance().tr(key);return label.equals(key)?key.substring(key.lastIndexOf('.')+1):label;}
}
