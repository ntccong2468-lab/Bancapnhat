package com.vncode.app.ui.products;

import com.vncode.app.features.products.*;
import com.vncode.app.features.listingcopy.CopyState;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Frozen selection, explicit approval, and durable recovery under the original shop. */
public final class ProductBulkEditPane extends BorderPane {
    private static final ExecutorService WRITES=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"vncode-product-edit");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ProductBulkEditService service;private final ShopReadCoordinator reads;
    private final Label title=new Label(),status=new Label(),notice=new Label();
    private final TextField value=new TextField(),number=new TextField(),type=new TextField(),name=new TextField(),country=new TextField(),accordance=new TextField(),productType=new TextField(),registry=new TextField();
    private final DatePicker from=new DatePicker(),until=new DatePicker();private final CheckBox endless=new CheckBox(),confirm=new CheckBox();
    private final Button back=new Button(),preview=new Button(),apply=new Button(),reconcile=new Button(),lookup=new Button(),chooseFile=new Button();
    private final TextArea detail=new TextArea();private final ComboBox<String> history=new ComboBox<>();private final VBox documents=new VBox(8);private final Map<Labeled,String> labels=new LinkedHashMap<>();
    private Shop shop;private List<ProductKey> keys=List.of();private String field="brand",jobId;private Path file;private long generation;private boolean disposed,busy;private Runnable onBack;
    public ProductBulkEditPane(){this(new ProductBulkEditService(),ShopReadCoordinator.shared());}
    public ProductBulkEditPane(ProductBulkEditService service,ShopReadCoordinator reads){
        this.service=service;this.reads=reads;setId("productBulkEditPane");setPadding(new Insets(18));
        title.getStyleClass().add("h2");var header=new HBox(12,back,title);setTop(new VBox(12,header,notice,status));
        preview.setId("editPreview");apply.setId("editApply");confirm.setId("editConfirm");lookup.setId("editLookup");value.setId("editValue");history.setId("editHistory");detail.setId("editDetail");
        detail.setEditable(false);detail.setWrapText(true);detail.setPrefRowCount(12);VBox.setVgrow(detail,Priority.ALWAYS);
        documents.getChildren().addAll(line("copy.doc.number",number),line("copy.doc.type",type),line("copy.doc.name",name),new FlowPane(8,8,from,until,endless),line("copy.doc.certificate_country",country),line("copy.doc.accordance_type",accordance),line("copy.doc.product_type",productType),line("copy.doc.link_to_registry",registry),chooseFile);
        var body=new VBox(12,new HBox(10,value,lookup),documents,history,detail);HBox.setHgrow(value,Priority.ALWAYS);body.setFillWidth(true);var scroll=new ScrollPane(body);scroll.setFitToWidth(true);setCenter(scroll);
        var actions=new FlowPane(10,8,preview,confirm,apply,reconcile);actions.setPadding(new Insets(12,0,0,0));setBottom(actions);
        label(back,"edit.back");label(preview,"edit.preview");label(apply,"edit.apply");label(reconcile,"edit.reconcile");label(lookup,"edit.lookup");label(chooseFile,"edit.file");label(confirm,"edit.confirm");label(endless,"copy.doc.isEndless");
        back.setOnAction(e->{if(onBack!=null)onBack.run();});confirm.selectedProperty().addListener((o,a,b)->buttons());preview.setOnAction(e->prepare());apply.setOnAction(e->submit());reconcile.setOnAction(e->reconcile());lookup.setOnAction(e->lookup());
        chooseFile.setOnAction(e->{var chooser=new FileChooser();chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF / JPEG / PNG","*.pdf","*.jpg","*.jpeg","*.png"));var picked=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(picked!=null){file=picked.toPath();chooseFile.setText(picked.getName());invalidate();}});
        for(var input:List.of(value,number,type,name,country,accordance,productType,registry))input.textProperty().addListener((o,a,b)->invalidate());from.valueProperty().addListener((o,a,b)->invalidate());until.valueProperty().addListener((o,a,b)->invalidate());endless.selectedProperty().addListener((o,a,b)->invalidate());
        history.setOnAction(e->{if(history.getValue()!=null){jobId=history.getValue();confirm.setSelected(false);Shop owner=shop;String id=jobId;read(()->service.result(owner,id),this::render);}});applyTranslations();buttons();
    }
    private HBox line(String key,TextField input){var l=new Label();label(l,key);l.setMinWidth(140);HBox.setHgrow(input,Priority.ALWAYS);return new HBox(8,l,input);}
    private void label(Labeled widget,String key){labels.put(widget,key);widget.setText(I18nService.getInstance().tr(key));}
    public void setOnBack(Runnable callback){onBack=callback;}
    public List<ProductKey> frozenKeys(){return keys;}
    public void setShop(Shop owner){generation++;shop=owner;keys=List.of();jobId=null;busy=false;confirm.setSelected(false);detail.clear();status.setText("");history.getItems().clear();if(owner!=null)read(()->service.list(owner),ids->history.getItems().setAll(ids));buttons();}
    public void open(Shop owner,String field,List<ProductKey> selected){setShop(owner);this.field=field;keys=List.copyOf(selected);file=null;value.clear();number.clear();type.clear();from.setValue(null);until.setValue(null);endless.setSelected(false);documents.setVisible(field.equals("documents"));documents.setManaged(documents.isVisible());value.setVisible(!documents.isVisible());value.setManaged(value.isVisible());lookup.setVisible(field.equals("tnved"));lookup.setManaged(lookup.isVisible());applyTranslations();buttons();}
    private void invalidate(){jobId=null;confirm.setSelected(false);buttons();}
    private ProductBulkEdit edit(){if(!field.equals("documents"))return new ProductBulkEdit(field,Arrays.stream(value.getText().split("\\|",-1)).map(String::strip).toList(),null,Map.of());if(number.getText().isBlank()||type.getText().isBlank()||from.getValue()==null||(!endless.isSelected()&&until.getValue()==null))throw new IllegalArgumentException("Document metadata missing");var metadata=new HashMap<String,String>();metadata.put("isEndless",Boolean.toString(endless.isSelected()));metadata.put("certificate_type",type.getText().strip());metadata.put("certificate_country",country.getText().strip());metadata.put("accordance_type",accordance.getText().strip());metadata.put("name",name.getText().strip());metadata.put("product_type",productType.getText().strip());metadata.put("link_to_registry",registry.getText().strip());metadata.put("type",type.getText().strip());var doc=new ProductDocument("",type.getText().strip(),number.getText().strip(),from.getValue(),until.getValue(),"","",file==null?null:file.toUri(),shop.getMarketplace(),DocumentState.UNVERIFIED,"",null,metadata);return new ProductBulkEdit(field,List.of(),List.of(doc),Map.of());}
    private void prepare(){if(shop==null||keys.isEmpty()||busy)return;final ProductBulkEdit e;try{e=edit();}catch(RuntimeException invalid){status.setText(tr("edit.needs_data"));return;}var owner=shop;var selected=keys;read(()->service.preview(owner,selected,e),p->{jobId=p.id();render(new ProductBulkEditService.ProductEditResult(p.id(),p.items(),false));});}
    private void submit(){if(shop==null||jobId==null||busy||!confirm.isSelected())return;var owner=shop;String id=jobId;mutate(()->service.apply(owner,id,true));}
    private void reconcile(){if(shop==null||jobId==null||busy)return;var owner=shop;String id=jobId;mutate(()->service.reconcile(owner,id));}
    private void mutate(Callable<ProductBulkEditService.ProductEditResult> work){long epoch=generation;busy=true;confirm.setSelected(false);buttons();try{CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception e){throw new CompletionException(e);}},WRITES).whenComplete((r,e)->Platform.runLater(()->{if(disposed||epoch!=generation)return;busy=false;if(e!=null)status.setText(tr("edit.reconcile_hint"));else render(r);buttons();}));}catch(RejectedExecutionException full){busy=false;status.setText(tr("edit.busy"));buttons();}}
    private <T>void read(Callable<T> work,Consumer<T> done){if(shop==null)return;long epoch=generation;busy=true;buttons();reads.submit(new ShopReadCoordinator.Key(shop.getId(),"product-edit",UUID.randomUUID().toString()),true,work).whenComplete((r,e)->Platform.runLater(()->{if(disposed||epoch!=generation)return;busy=false;if(e!=null)status.setText(tr("edit.needs_data"));else done.accept(r);buttons();}));}
    private void render(ProductBulkEditService.ProductEditResult result){jobId=result.id();var text=new StringBuilder();boolean ready=true;for(var item:result.items()){text.append(item.key().cardId()).append(" · ").append(tr("copy.state."+item.state().name())).append('\n');text.append(tr("edit.before")).append(": ").append(item.before()).append('\n');text.append(tr("edit.after")).append(": ").append(item.changes().isEmpty()?item.documents().stream().map(ProductDocument::number).toList():item.changes()).append('\n');text.append(item.preview().preview().requestJson()).append("\n\n");for(var issue:item.issues())text.append(tr("edit.needs_data")).append(": ").append(issue.code()).append(" / ").append(issue.field()).append('\n');ready&=item.state()==CopyState.AWAITING_CONFIRMATION&&item.preview().preview().publishable();}detail.setText(text.toString());apply.setUserData(ready);confirm.setSelected(false);status.setText(tr("edit.review"));buttons();}
    private void buttons(){preview.setDisable(busy||shop==null||keys.isEmpty());apply.setDisable(busy||jobId==null||!Boolean.TRUE.equals(apply.getUserData())||!confirm.isSelected());reconcile.setDisable(busy||jobId==null);confirm.setDisable(busy);}
    private void lookup(){var catalog=new com.vncode.app.features.tnved.TnvedCatalog(AppPaths.appDataDir().resolve("tnved-catalog.db"));var dialog=new Dialog<Void>();dialog.setTitle(tr("edit.lookup"));dialog.getDialogPane().setContent(new com.vncode.app.ui.tnved.TnvedPane(catalog));dialog.getDialogPane().setPrefSize(960,560);dialog.getDialogPane().getButtonTypes().add(new ButtonType(tr("edit.back"),ButtonBar.ButtonData.CANCEL_CLOSE));dialog.show();}
    public void applyTranslations(){labels.forEach((w,k)->w.setText(tr(k)));title.setText(tr("products.edit_"+field)+" · "+keys.size());notice.setText(tr("edit.notice"));notice.setWrapText(true);value.setPromptText(tr("edit.value"));history.setPromptText(tr("edit.history"));from.setPromptText(tr("copy.doc.from"));until.setPromptText(tr("copy.doc.to"));}
    private String tr(String key){return I18nService.getInstance().tr(key);}
    public void dispose(){disposed=true;generation++;}
}
