package com.vncode.app.ui.products;

import com.vncode.app.features.products.*;
import com.vncode.app.features.products.ProductBulkEditService.*;
import com.vncode.app.features.listingcopy.CopyState;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Own-shop draft → explicit confirmation → one send → bounded readback. */
public final class ProductBulkEditPane extends BorderPane {
    private final ProductBulkEditService service;
    private final ProductSuggestionService suggestionsService;
    private final ShopReadCoordinator reads;
    private final Label title=new Label(),status=new Label(),notice=new Label(),suggestionStatus=new Label();
    private final TextField value=new TextField(),number=new TextField(),type=new TextField(),name=new TextField(),country=new TextField(),accordance=new TextField(),productType=new TextField(),registry=new TextField();
    private final DatePicker from=new DatePicker(),until=new DatePicker();
    private final CheckBox endless=new CheckBox(),confirm=new CheckBox();
    private final Button back=new Button(),preview=new Button(),apply=new Button(),reconcile=new Button(),lookup=new Button(),chooseFile=new Button(),useSuggestion=new Button();
    private final TextArea detail=new TextArea(),technical=new TextArea();
    private final TableView<EditItem> results=new TableView<>();
    private final ListView<String> suggestions=new ListView<>();
    private final ComboBox<String> history=new ComboBox<>();
    private final VBox documents=new VBox(8),values=new VBox(8);
    private final Map<Labeled,String> labels=new LinkedHashMap<>();
    private final PauseTransition suggestionDelay=new PauseTransition(Duration.millis(350)),readbackDelay=new PauseTransition(Duration.seconds(2));
    private Shop shop;
    private List<ProductKey> keys=List.of();
    private String field="brand",jobId;
    private Path file;
    private long generation,revision,suggestionTicket,previewRevision=-1;
    private int readbackRounds;
    private boolean disposed,busy,resetting,ready;
    private Runnable onBack,onSaved;
    private String notifiedJob;

    public ProductBulkEditPane(){this(new ProductBulkEditService(),ShopReadCoordinator.shared());}
    public ProductBulkEditPane(ProductBulkEditService service,ShopReadCoordinator reads){this(service,reads,new ProductSuggestionService());}
    public ProductBulkEditPane(ProductBulkEditService service,ShopReadCoordinator reads,ProductSuggestionService suggestionsService){
        this.service=service;this.reads=reads;this.suggestionsService=suggestionsService;
        setId("productBulkEditPane");setPadding(new Insets(18));setMinSize(0,0);
        DocumentDateInput.configure(from);DocumentDateInput.configure(until);
        from.setId("editDocumentFrom");until.setId("editDocumentUntil");
        name.setId("editDocumentName");country.setId("editDocumentCountry");accordance.setId("editDocumentAccordance");productType.setId("editDocumentProductType");registry.setId("editDocumentRegistry");
        title.getStyleClass().add("h2");notice.setWrapText(true);status.setWrapText(true);suggestionStatus.setWrapText(true);
        var header=new FlowPane(12,8,back,title);setTop(new VBox(10,header,notice));
        preview.setId("editPreview");apply.setId("editApply");reconcile.setId("editReconcile");confirm.setId("editConfirm");lookup.setId("editLookup");
        value.setId("editValue");history.setId("editHistory");detail.setId("editDetail");status.setId("editStatus");
        suggestions.setId("editSuggestions");useSuggestion.setId("editUseSuggestion");suggestionStatus.setId("editSuggestionStatus");results.setId("editResults");
        suggestions.setPrefHeight(108);suggestions.setMinHeight(60);
        useSuggestion.disableProperty().bind(suggestions.getSelectionModel().selectedItemProperty().isNull());
        var input=new HBox(8,value,lookup);HBox.setHgrow(value,Priority.ALWAYS);
        values.getChildren().addAll(input,suggestionStatus,suggestions,useSuggestion);
        documents.getChildren().addAll(line("copy.doc.number",number),line("copy.doc.type",type),line("copy.doc.name",name),new FlowPane(8,8,from,until,endless),line("copy.doc.certificate_country",country),line("copy.doc.accordance_type",accordance),line("copy.doc.product_type",productType),line("copy.doc.link_to_registry",registry),chooseFile);
        results.setPrefHeight(220);results.setMinHeight(120);results.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("products.article",i->i.key().cardId()+" / "+i.key().variantId(),140);
        column("edit.before",i->String.join("; ",i.before()),170);
        column("edit.after",this::after,170);
        column("znack.registration.status",i->tr("copy.state."+i.state().name()),150);
        results.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showItem(b));
        detail.setEditable(false);detail.setWrapText(true);detail.setPrefRowCount(3);
        technical.setEditable(false);technical.setWrapText(true);technical.setPrefRowCount(5);
        var advanced=new TitledPane(tr("edit.technical"),technical);advanced.setExpanded(false);advanced.setId("editTechnical");
        var body=new VBox(10,values,documents,history,results,detail,advanced);body.setFillWidth(true);body.setPadding(new Insets(12,0,0,0));
        var scroll=new ScrollPane(body);scroll.setFitToWidth(true);setCenter(scroll);
        var actions=new FlowPane(10,8,preview,apply,reconcile);apply.getStyleClass().add("btn-primary");
        var footer=new VBox(8,status,confirm,actions);footer.setPadding(new Insets(12,0,0,0));setBottom(footer);
        label(back,"edit.back");label(preview,"edit.preview");label(apply,"edit.apply");label(reconcile,"edit.reconcile");label(lookup,"edit.lookup");label(chooseFile,"edit.file");label(confirm,"edit.confirm");label(endless,"copy.doc.isEndless");label(useSuggestion,"edit.use_suggestion");label(advanced,"edit.technical");
        back.setOnAction(e->{if(onBack!=null)onBack.run();});confirm.selectedProperty().addListener((o,a,b)->buttons());
        preview.setOnAction(e->prepare());apply.setOnAction(e->submit());reconcile.setOnAction(e->reconcile(false));lookup.setOnAction(e->lookup());
        useSuggestion.setOnAction(e->useSelected());suggestions.setOnMouseClicked(e->{if(e.getClickCount()==2)useSelected();});
        suggestionDelay.setOnFinished(e->loadSuggestions());readbackDelay.setOnFinished(e->reconcile(true));
        chooseFile.setOnAction(e->{var chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF / JPEG / PNG","*.pdf","*.jpg","*.jpeg","*.png"));var picked=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(picked!=null){file=picked.toPath();chooseFile.setText(picked.getName());invalidate();}});
        for(var inputField:List.of(value,number,type,name,country,accordance,productType,registry))inputField.textProperty().addListener((o,a,b)->invalidate());
        value.textProperty().addListener((o,a,b)->{if(!resetting&&!field.equals("documents")){suggestionTicket++;suggestionDelay.playFromStart();}});
        from.valueProperty().addListener((o,a,b)->invalidate());until.valueProperty().addListener((o,a,b)->invalidate());endless.selectedProperty().addListener((o,a,b)->invalidate());
        history.setOnAction(e->{if(history.getValue()!=null&&!busy){jobId=history.getValue();ready=false;previewRevision=-1;confirm.setSelected(false);var owner=shop;String id=jobId;readResult(owner,id);}});
        applyTranslations();buttons();
    }
    private void column(String label,Function<EditItem,String> text,double width){
        var c=new TableColumn<EditItem,String>();c.textProperty().set(tr(label));c.setUserData(label);c.setMinWidth(95);c.setPrefWidth(width);
        c.setCellValueFactory(v->new ReadOnlyStringWrapper(text.apply(v.getValue())));
        c.setCellFactory(v->new TableCell<>(){protected void updateItem(String s,boolean empty){super.updateItem(s,empty);setText(empty?null:s);setTooltip(empty||s==null?null:new Tooltip(s));}});results.getColumns().add(c);
    }
    private HBox line(String key,TextField input){var l=new Label();label(l,key);l.setMinWidth(140);HBox.setHgrow(input,Priority.ALWAYS);return new HBox(8,l,input);}
    private void label(Labeled widget,String key){labels.put(widget,key);widget.setText(tr(key));}
    public void setOnBack(Runnable callback){onBack=callback;}
    public void setOnSaved(Runnable callback){onSaved=callback;}
    public List<ProductKey> frozenKeys(){return keys;}
    public void setShop(Shop owner){
        generation++;suggestionTicket++;suggestionDelay.stop();readbackDelay.stop();shop=owner;keys=List.of();jobId=null;notifiedJob=null;
        busy=false;ready=false;previewRevision=-1;confirm.setSelected(false);results.getItems().clear();detail.clear();technical.clear();status.setText("");
        suggestions.getItems().clear();suggestionStatus.setText("");history.getItems().clear();
        if(owner!=null){long epoch=generation;reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-edit-history",UUID.randomUUID().toString()),()->service.list(owner)).whenComplete((ids,error)->Platform.runLater(()->{if(!disposed&&epoch==generation&&error==null)history.getItems().setAll(ids);}));}
        buttons();
    }
    public void open(Shop owner,String field,List<ProductKey> selected){
        setShop(owner);this.field=field;keys=List.copyOf(selected);file=null;resetting=true;
        for(var input:List.of(value,number,type,name,country,accordance,productType,registry))input.clear();
        from.setValue(null);until.setValue(null);endless.setSelected(false);chooseFile.setText(tr("edit.file"));resetting=false;revision++;
        documents.setVisible(field.equals("documents"));documents.setManaged(documents.isVisible());values.setVisible(!documents.isVisible());values.setManaged(values.isVisible());
        lookup.setVisible(field.equals("tnved"));lookup.setManaged(lookup.isVisible());applyTranslations();status.setText(tr("edit.enter_value"));buttons();
        if(!field.equals("documents"))suggestionDelay.playFromStart();
    }
    private void invalidate(){if(resetting)return;revision++;jobId=null;ready=false;previewRevision=-1;confirm.setSelected(false);readbackDelay.stop();status.setText(tr("edit.preview_required"));buttons();}
    private void useSelected(){
        String selected=suggestions.getSelectionModel().getSelectedItem();if(selected==null||busy)return;
        String text=value.getText();int separator=text.lastIndexOf('|');value.setText(separator<0?selected:text.substring(0,separator+1)+selected);value.requestFocus();
    }
    private void loadSuggestions(){
        if(disposed||shop==null||keys.isEmpty()||field.equals("documents"))return;
        var owner=shop;var selected=keys;String editedField=field,text=value.getText();String q=text.substring(text.lastIndexOf('|')+1).strip();long epoch=generation,ticket=++suggestionTicket;
        suggestionStatus.setText(tr("edit.suggestions_loading"));
        reads.submit(new ShopReadCoordinator.Key(owner.getId(),"product-edit-values",UUID.randomUUID().toString()),true,()->suggestionsService.suggest(owner,selected,editedField,q)).whenComplete((found,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation||ticket!=suggestionTicket)return;
            suggestions.getItems().setAll(error==null?found.values():List.of());
            suggestionStatus.setText(error!=null?tr("edit.suggestions_failed"):tr(found.marketplaceValues()?"edit.suggestions_marketplace":"edit.suggestions_common")+" · "+found.category()+"\n"+tr("edit.suggestions_scope"));
            if(error==null&&found.values().isEmpty())suggestionStatus.setText(suggestionStatus.getText()+"\n"+tr("edit.suggestions_empty"));
        }));
    }
    private ProductBulkEdit edit(){
        if(!field.equals("documents"))return new ProductBulkEdit(field,Arrays.stream(value.getText().split("\\|",-1)).map(String::strip).toList(),null,Map.of());
        DocumentDateInput.commit(from);if(!endless.isSelected())DocumentDateInput.commit(until);
        if(number.getText().isBlank()||type.getText().isBlank()||from.getValue()==null||(!endless.isSelected()&&until.getValue()==null))throw new IllegalArgumentException("Document metadata missing");
        var metadata=new HashMap<String,String>();metadata.put("isEndless",Boolean.toString(endless.isSelected()));metadata.put("certificate_type",type.getText().strip());metadata.put("certificate_country",country.getText().strip());metadata.put("accordance_type",accordance.getText().strip());metadata.put("name",name.getText().strip());metadata.put("product_type",productType.getText().strip());metadata.put("link_to_registry",registry.getText().strip());metadata.put("type",type.getText().strip());
        var doc=new ProductDocument("",type.getText().strip(),number.getText().strip(),from.getValue(),until.getValue(),"","",file==null?null:file.toUri(),shop.getMarketplace(),DocumentState.UNVERIFIED,"",null,metadata);
        return new ProductBulkEdit(field,List.of(),List.of(doc),Map.of());
    }
    private void prepare(){
        if(shop==null||keys.isEmpty()||busy)return;final ProductBulkEdit edit;
        try{edit=edit();}catch(RuntimeException invalid){status.setText(tr("date.invalid".equals(invalid.getMessage())?"date.invalid":"edit.needs_data"));return;}
        var owner=shop;var selected=keys;long epoch=generation,inputRevision=revision;busy=true;ready=false;status.setText(tr("edit.preview_loading"));buttons();
        reads.submit(new ShopReadCoordinator.Key(owner.getId(),"product-edit-preview",UUID.randomUUID().toString()),true,()->service.preview(owner,selected,edit)).whenComplete((p,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;busy=false;
            if(inputRevision!=revision){status.setText(tr("edit.preview_required"));buttons();return;}
            if(error!=null){status.setText(FriendlyErrorService.format(unwrap(error)));buttons();return;}
            previewRevision=inputRevision;render(new ProductEditResult(p.id(),p.items(),false));
        }));
    }
    private void submit(){
        if(shop==null||jobId==null||busy||!ready||previewRevision!=revision||!confirm.isSelected())return;
        var owner=shop;String id=jobId;readbackRounds=0;
        mutate(owner,id,()->{var sent=service.apply(owner,id,true);return pending(sent)?service.reconcile(owner,id):sent;},true);
    }
    private void reconcile(boolean automatic){
        if(shop==null||jobId==null||busy)return;var owner=shop;String id=jobId;
        if(!automatic)readbackRounds=0;
        mutate(owner,id,()->service.reconcile(owner,id),true);
    }
    private void mutate(Shop owner,String id,Callable<ProductEditResult> work,boolean poll){
        long epoch=generation;busy=true;ready=false;confirm.setSelected(false);readbackDelay.stop();status.setText(tr("edit.saving"));buttons();
        SellerMutationExecutor.submit(owner,work).whenComplete((result,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;busy=false;
            if(error!=null){previewRevision=-1;recoverResult(owner,id,epoch,FriendlyErrorService.format(unwrap(error)));return;}
            render(result);
            if(poll&&pending(result)&&++readbackRounds<3)readbackDelay.playFromStart();
        }));
    }
    private void recoverResult(Shop owner,String id,long epoch,String errorMessage){
        busy=true;buttons();
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-edit-recovery",id),()->service.result(owner,id)).whenComplete((r,e)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;busy=false;if(e==null)render(r);
            status.setText(errorMessage+"\n"+tr("edit.reconcile_hint"));buttons();
        }));
    }
    private void readResult(Shop owner,String id){
        long epoch=generation;busy=true;buttons();
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-edit-result",id),()->service.result(owner,id)).whenComplete((result,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation||!Objects.equals(id,jobId))return;busy=false;
            if(error!=null)status.setText(FriendlyErrorService.format(unwrap(error)));else render(result);buttons();
        }));
    }
    private static boolean pending(ProductEditResult r){return r.items().stream().anyMatch(i->i.state()==CopyState.REMOTE_PROCESSING||i.state()==CopyState.RECONCILE_REQUIRED);}
    private void render(ProductEditResult result){
        jobId=result.id();TableRows.merge(results.getItems(),result.items(),i->i.key().cardId());
        ready=!result.items().isEmpty()&&result.items().stream().allMatch(i->i.state()==CopyState.AWAITING_CONFIRMATION&&i.preview().preview().publishable());
        boolean saved=!result.items().isEmpty()&&result.items().stream().allMatch(i->i.state()==CopyState.RECONCILED);
        confirm.setSelected(false);results.getSelectionModel().selectFirst();
        status.setText(tr(saved?"edit.saved":pending(result)?"edit.readback_pending":ready?"edit.review":"edit.result_needs_action"));
        if(saved&&!Objects.equals(notifiedJob,result.id())){notifiedJob=result.id();if(onSaved!=null)onSaved.run();}
        buttons();
    }
    private String after(EditItem i){return i.changes().isEmpty()?i.documents()==null?"":String.join("; ",i.documents().stream().map(ProductDocument::number).toList()):String.join("; ",i.changes().values().stream().flatMap(Collection::stream).toList());}
    private void showItem(EditItem i){
        if(i==null){detail.clear();technical.clear();return;}
        detail.setText(i.key().cardId()+" / "+i.key().variantId()+"\n"+tr("copy.state."+i.state().name())+(i.issues().isEmpty()?"":"\n"+i.issues().stream().map(v->v.code()+" · "+v.field()).collect(java.util.stream.Collectors.joining("\n"))));
        technical.setText(i.preview().preview().requestJson());
    }
    private void buttons(){
        preview.setDisable(busy||shop==null||keys.isEmpty());
        apply.setDisable(busy||jobId==null||!ready||previewRevision!=revision||!confirm.isSelected());
        reconcile.setDisable(busy||jobId==null||results.getItems().stream().noneMatch(i->i.state()==CopyState.REMOTE_PROCESSING||i.state()==CopyState.RECONCILE_REQUIRED));
        confirm.setDisable(busy||!ready||previewRevision!=revision);history.setDisable(busy);
        // A preview may become stale while typing; a send must retain its frozen inputs.
        boolean sending=busy&&jobId!=null;values.setDisable(sending);documents.setDisable(sending);
    }
    private void lookup(){
        var catalog=new com.vncode.app.features.tnved.TnvedCatalog(AppPaths.appDataDir().resolve("tnved-catalog.db"));var dialog=new Dialog<Void>();dialog.setTitle(tr("edit.lookup"));
        if(getScene()!=null)dialog.initOwner(getScene().getWindow());var picker=new com.vncode.app.ui.tnved.TnvedPane(catalog);
        picker.setOnSelect(node->{value.setText(node.code());dialog.close();});dialog.getDialogPane().setContent(picker);dialog.getDialogPane().setPrefSize(960,560);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType(tr("edit.back"),ButtonBar.ButtonData.CANCEL_CLOSE));DialogLayout.install(dialog.getDialogPane());dialog.show();
    }
    public void applyTranslations(){
        labels.forEach((w,k)->w.setText(tr(k)));title.setText(tr("products.edit_"+field)+" · "+keys.size());notice.setText(tr("edit.notice")+(shop==null?"":"\n"+shop.getName()+" · "+shop.getMarketplace()));
        value.setPromptText(tr("edit.value"));history.setPromptText(tr("edit.history"));from.setPromptText(tr("copy.doc.from"));until.setPromptText(tr("copy.doc.to"));
        for(var c:results.getColumns())c.setText(tr((String)c.getUserData()));results.refresh();
    }
    private static Throwable unwrap(Throwable e){return (e instanceof CompletionException||e instanceof ExecutionException)&&e.getCause()!=null?e.getCause():e;}
    private String tr(String key){return I18nService.getInstance().tr(key);}
    public void dispose(){disposed=true;generation++;suggestionTicket++;suggestionDelay.stop();readbackDelay.stop();}
}
