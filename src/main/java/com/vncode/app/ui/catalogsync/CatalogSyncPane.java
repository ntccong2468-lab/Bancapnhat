package com.vncode.app.ui.catalogsync;

import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Insets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.vncode.app.features.catalogsync.CatalogSyncModels.*;

/** UI state never owns network work; pending child jobs remain recoverable after closing the view. */
public final class CatalogSyncPane extends BorderPane {
    private final Map<Field,CheckBox> fields=new EnumMap<>(Field.class);
    private final Map<GtinSyncModels.ProductKey,List<ProductDocument>> documents=new LinkedHashMap<>();
    private final Map<GtinSyncModels.ProductKey,Map<String,List<String>>> values=new LinkedHashMap<>();
    private final Button preview=new Button(),apply=new Button(),advance=new Button(),pause=new Button(),editDocument=new Button(),options=new Button(),editValue=new Button();
    private final CheckBox confirmed=new CheckBox();private final Label status=new Label(),scope=new Label();
    private final TableView<Row> products=new TableView<>();private final TableView<Diff> differences=new TableView<>();
    private final ComboBox<String> history=new ComboBox<>();private final Supplier<CatalogSyncService> factory;
    private CatalogSyncService service;private Shop shop;private List<Selection> selection=List.of();private Job job;private boolean busy,disposed;private long generation;
    public CatalogSyncPane(){this(CatalogSyncService::new);}
    CatalogSyncPane(Supplier<CatalogSyncService> factory){
        this.factory=factory;products.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);differences.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);products.setFixedCellSize(38);differences.setFixedCellSize(38);products.setPlaceholder(new Label(tr("catalogsync.empty")));differences.setPlaceholder(new Label(tr("catalogsync.empty")));history.setPromptText(tr("catalogsync.history"));preview.getStyleClass().add("btn-primary");apply.getStyleClass().add("btn-primary");setId("catalogSyncPane");setPadding(new Insets(12));
        var title=new Label(tr("catalogsync.title"));title.getStyleClass().add("h2");
        var choices=new FlowPane(10,8);for(Field field:Field.values()){
            var box=new CheckBox(tr("catalogsync.field."+field));box.setId("catalogSync"+field);fields.put(field,box);box.setSelected(Set.of(Field.GTIN,Field.TNVED,Field.MATERIAL,Field.DOCUMENTS).contains(field));box.selectedProperty().addListener((o,a,b)->invalidate());choices.getChildren().add(box);
        }
        var notice=new Label(tr("catalogsync.notice"));notice.setWrapText(true);scope.setWrapText(true);status.setWrapText(true);
        preview.setText(tr("catalogsync.preview"));apply.setText(tr("catalogsync.apply"));advance.setText(tr("catalogsync.advance"));pause.setText(tr("catalogsync.pause"));editValue.setText(tr("catalogsync.edit_value"));editDocument.setText(tr("catalogsync.document_edit"));options.setText(tr("catalogsync.document_options"));confirmed.setText(tr("catalogsync.confirm"));
        preview.setId("catalogSyncPreview");apply.setId("catalogSyncApply");advance.setId("catalogSyncAdvance");confirmed.setId("catalogSyncConfirm");history.setId("catalogSyncHistory");
        setTop(new VBox(10,title,scope,notice,choices,new FlowPane(8,8,preview,editValue,editDocument,options,history),status));
        column(products,"catalogsync.product",r->r.key().productId()+" / "+r.key().variantId());column(products,"catalogsync.gtin",r->r.catalog().gtin());column(products,"catalogsync.state",r->tr("catalogsync.state."+r.state()));column(products,"catalogsync.issue",r->issue(r.issue()));
        column(differences,"catalogsync.field",d->fieldName(d.field()));column(differences,"catalogsync.before",d->String.join(" | ",d.before()));column(differences,"catalogsync.after",d->String.join(" | ",d.after()));column(differences,"catalogsync.issue",d->issue(d.code()));
        products.setId("catalogSyncProducts");differences.setId("catalogSyncDifferences");
        var split=new SplitPane(products,differences);split.setOrientation(javafx.geometry.Orientation.VERTICAL);split.setDividerPositions(.45);BorderPane.setMargin(split,new Insets(10,0,10,0));setCenter(split);
        setBottom(new FlowPane(10,8,confirmed,apply,advance,pause));
        products.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{differences.getItems().setAll(b==null?List.of():b.differences());buttons();});confirmed.selectedProperty().addListener((o,a,b)->buttons());
        preview.setOnAction(e->preview());apply.setOnAction(e->run(true));advance.setOnAction(e->run(false));pause.setOnAction(e->{if(job==null)return;var owner=shop;String id=job.id();boolean paused=!job.paused();mutate(()->{service().pause(owner,id,paused);return service().read(owner,id);});});
        editValue.setOnAction(e->editValue());
        editDocument.setOnAction(e->editDocuments());options.setOnAction(e->documentOptions());
        history.setOnAction(e->{String id=history.getValue();if(id!=null&&shop!=null&&!busy){var owner=shop;read(()->service().read(owner,id),this::render);}});buttons();
    }
    public void open(Shop owner,List<Selection> selected){generation++;shop=owner==null?null:new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());selection=List.copyOf(selected);job=null;documents.clear();values.clear();products.getItems().clear();differences.getItems().clear();confirmed.setSelected(false);scope.setText(shop==null?"":shop.getName()+" · "+shop.getMarketplace().badge()+" · "+selection.size());buttons();if(shop!=null){var captured=shop;read(()->service().list(captured),ids->history.getItems().setAll(ids));}}
    private synchronized CatalogSyncService service(){if(service==null)service=factory.get();return service;}
    private void preview(){if(shop==null||busy||selection.isEmpty())return;var owner=shop;var selected=selection;var chosen=new LinkedHashSet<Field>();fields.forEach((field,box)->{if(box.isSelected())chosen.add(field);});if(chosen.isEmpty()){status.setText(tr("catalogsync.select_fields"));return;}var request=new Options(chosen,documents,values);read(()->service().preview(owner,selected,request),this::render);}
    private void run(boolean approve){if(job==null||busy||approve&&!confirmed.isSelected())return;var owner=shop;String id=job.id();mutate(()->service().run(owner,id,approve));}
    private void mutate(Callable<Job> work){start(work,this::render,true);}
    private <T>void read(Callable<T> work,Consumer<T> accept){start(work,accept,false);}
    private <T>void start(Callable<T> work,Consumer<T> accept,boolean mutation){
        long epoch=generation;busy=true;buttons();CompletableFuture<T> result;
        try{result=mutation?SellerMutationExecutor.submit(work):ShopReadCoordinator.shared().submit(new ShopReadCoordinator.Key(shop.getId(),"catalog-sync",UUID.randomUUID().toString()),true,work);}
        catch(RejectedExecutionException full){busy=false;status.setText(tr("runtime.queue_full"));buttons();return;}
        result.whenComplete((value,error)->Platform.runLater(()->{if(disposed||epoch!=generation)return;busy=false;if(error!=null)status.setText(tr("catalogsync.failed")+" · "+safe(error));else accept.accept(value);buttons();}));
    }
    private void render(Job value){job=value;confirmed.setSelected(false);products.getItems().setAll(value.rows());if(!value.rows().isEmpty())products.getSelectionModel().selectFirst();long done=value.rows().stream().filter(r->r.state()==State.SUCCEEDED).count();status.setText(tr("catalogsync.summary")+": "+done+" / "+value.rows().size()+" · "+tr("catalogsync.ready")+": "+value.readyCount());buttons();}
    private void editDocuments(){
        if(shop==null||busy)return;var selected=products.getSelectionModel().getSelectedItem();
        Selection item=selected!=null?new Selection(selected.key(),selected.catalog().gtin()):selection.size()==1?selection.getFirst():null;
        if(item==null){status.setText(tr("catalogsync.select_product"));return;}
        var owner=shop;var supplied=documents.get(item.key());
        read(()->supplied!=null?supplied:new GtinSyncRepository().catalogItem(owner.getId(),owner.getMarketplace(),item.gtin()).details().documents().stream().map(d->CatalogSyncMapper.document(d,owner.getMarketplace())).toList(),docs->{
            ProductDocument initial;
            if(docs.size()>1){var choose=new ChoiceDialog<>(docs.getFirst().number(),docs.stream().map(ProductDocument::number).toList());AlertService.applyTheme(choose);choose.setTitle(tr("catalogsync.document_edit"));var number=choose.showAndWait();if(number.isEmpty())return;initial=docs.stream().filter(d->d.number().equals(number.get())).findFirst().orElseThrow();}
            else initial=docs.isEmpty()?new ProductDocument("","DECLARATION","",null,null,"","",null,owner.getMarketplace(),DocumentState.UNVERIFIED,"",null,Map.of()):docs.getFirst();
            var original=initial;CatalogDocumentEditor.show(getScene().getWindow(),owner.getMarketplace(),initial).ifPresent(updated->{var combined=new ArrayList<>(docs);combined.removeIf(d->d==original||d.number().equals(original.number()));combined.add(updated);for(var sibling:selection)if(sibling.key().productId().equals(item.key().productId()))documents.put(sibling.key(),List.copyOf(combined));invalidate();status.setText(tr("catalogsync.document_saved"));});
        });
    }
    private void editValue(){
        var row=products.getSelectionModel().getSelectedItem();if(row==null||busy)return;
        var available=List.of(Field.TNVED,Field.MATERIAL,Field.BRAND,Field.TITLE,Field.GENDER,Field.COUNTRY,Field.COLOR);
        var labels=available.stream().map(f->tr("catalogsync.field."+f)).toList();var picker=new ChoiceDialog<>(labels.getFirst(),labels);AlertService.applyTheme(picker);picker.setTitle(tr("catalogsync.edit_value"));var picked=picker.showAndWait();if(picked.isEmpty())return;
        Field field=available.get(labels.indexOf(picked.get()));String name=field.name().toLowerCase(Locale.ROOT);
        var current=values.getOrDefault(row.key(),Map.of()).getOrDefault(name,row.catalog().details().values(name));
        var input=new TextInputDialog(String.join(" | ",current));AlertService.applyTheme(input);input.setTitle(tr("catalogsync.edit_value"));input.setHeaderText(tr("catalogsync.field."+field));input.setContentText(tr("catalogsync.value_hint"));
        input.showAndWait().ifPresent(text->{var proposed=Arrays.stream(text.split("\\|",-1)).map(String::strip).toList();
            if(proposed.stream().anyMatch(String::isBlank)||field==Field.TNVED&&(proposed.size()!=1||!proposed.getFirst().matches("[0-9]{10}"))){status.setText(tr("catalogsync.select_fields"));return;}
            for(var sibling:selection)if(sibling.key().productId().equals(row.key().productId())){var edited=new LinkedHashMap<>(values.getOrDefault(sibling.key(),Map.of()));edited.put(name,proposed);values.put(sibling.key(),Map.copyOf(edited));}
            fields.get(field).setSelected(true);invalidate();status.setText(tr("catalogsync.value_saved"));
        });
    }
    private void documentOptions(){if(shop==null||busy)return;var owner=shop;read(()->new com.vncode.app.integration.listing.ListingHttpClient(owner).call("v2/product/certification/options",new com.google.gson.JsonObject(),false).toString(),value->{var dialog=new Dialog<Void>();AlertService.applyTheme(dialog);dialog.setTitle(tr("catalogsync.document_options"));var text=new TextArea(optionsDescription(com.google.gson.JsonParser.parseString(value)));text.setEditable(false);text.setWrapText(true);dialog.getDialogPane().setContent(text);dialog.getDialogPane().setPrefSize(780,500);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.show();});}
    private static String optionsDescription(com.google.gson.JsonElement value){var out=new StringBuilder();describeOptions(value,"",0,out);return out.toString();}
    private static void describeOptions(com.google.gson.JsonElement value,String path,int depth,StringBuilder out){
        if(value==null||value.isJsonNull()||depth>8||out.length()>50000)return;
        if(value.isJsonArray()){for(var entry:value.getAsJsonArray())describeOptions(entry,path,depth+1,out);}
        else if(value.isJsonObject()){
            var object=value.getAsJsonObject();if(object.has("value")&&object.get("value").isJsonPrimitive()){
                String label=object.has("name")&&object.get("name").isJsonPrimitive()?object.get("name").getAsString():object.has("label")&&object.get("label").isJsonPrimitive()?object.get("label").getAsString():"";
                out.append(path).append(": ").append(object.get("value").getAsString()).append(" — ").append(label).append('\n');
            }else for(var entry:object.entrySet())describeOptions(entry.getValue(),path.isBlank()?entry.getKey():path+" / "+entry.getKey(),depth+1,out);
        }else if(value.isJsonPrimitive())out.append(path).append(": ").append(value.getAsString()).append('\n');
    }
    private void invalidate(){job=null;confirmed.setSelected(false);buttons();}
    private void buttons(){
        preview.setDisable(busy||shop==null||selection.isEmpty());apply.setDisable(busy||job==null||job.confirmed()||job.readyCount()==0||!confirmed.isSelected());advance.setDisable(busy||job==null||!job.confirmed()||job.paused());pause.setDisable(busy||job==null||!job.confirmed());pause.setText(tr(job!=null&&job.paused()?"catalogsync.resume":"catalogsync.pause"));editValue.setDisable(busy||shop==null||products.getSelectionModel().getSelectedItem()==null);editDocument.setDisable(busy||shop==null);options.setDisable(busy||shop==null||shop.getMarketplace()!=com.vncode.app.integration.marketplace.Marketplace.OZON);confirmed.setDisable(busy||job==null||job.confirmed());history.setDisable(busy);fields.values().forEach(box->box.setDisable(busy));
    }
    public void dispose(){disposed=true;generation++;}
    private static <T>void column(TableView<T> table,String key,Function<T,String> value){var column=new TableColumn<T,String>(tr(key));column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));column.setPrefWidth(220);table.getColumns().add(column);}
    private static String safe(Throwable error){while(error instanceof CompletionException&&error.getCause()!=null)error=error.getCause();String code=error.getMessage();return code!=null&&code.matches("[A-Z][A-Z0-9_]{1,80}")?issue(code):tr("catalogsync.readback_hint");}
    private static String fieldName(String field){try{return tr("catalogsync.field."+Field.valueOf(field.toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException unknown){return field;}}
    private static String issue(String code){if(code==null||code.isBlank())return "";String key="catalogsync.issue."+code;String value=tr(key);if(!value.equals(key))return value;String common=tr("copy.issue."+code);return common.equals("copy.issue."+code)?code:common;}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
