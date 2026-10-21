package com.vncode.app.ui.listingcopy;

import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One wizard for both marketplaces. Read generations never own outgoing mutation callbacks. */
public final class ListingCopyPane extends BorderPane {
    private final ListingCopyService service;private final ShopReadCoordinator reads;private final I18nService i18n=I18nService.getInstance();
    private final ThumbnailLoader thumbnails=new ThumbnailLoader();
    private final Map<Labeled,String> labels=new WeakHashMap<>();
    private final ComboBox<ProductKey> sourceCard=new ComboBox<>();private final ComboBox<Shop> editorShop=new ComboBox<>();
    private final FlowPane destinationBox=new FlowPane(12,8);
    private final VBox variantBox=new VBox(6),mappingBox=new VBox(6),documentBox=new VBox(6),previewBox=new VBox(6),configuration=new VBox(12);
    private final TitledPane details=new TitledPane();
    private final TitledPane generalDetails=new TitledPane(),variantDetails=new TitledPane(),attributeDetails=new TitledPane(),documentDetails=new TitledPane();
    private final Label inheritedSummary=new Label();
    private final ProgressBar postingProgress=new ProgressBar(0);
    private final VBox batchErrors=new VBox(6);
    private final javafx.animation.PauseTransition following=new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
    private final Map<String,Integer> followAttempts=new HashMap<>();
    private final ListView<CopyJob> jobs=new ListView<>();private final Label status=new Label();
    private final CheckBox confirm=check("confirm"),marked=check("marked");
    private final Button prepare=button("prepare"),publish=button("publish"),reconcile=button("reconcile"),editJob=button("edit_job"),choose=button("choose_source");
    private final TextField title=new TextField(),brand=new TextField(),price=new TextField(),article=new TextField();private final TextArea description=new TextArea();
    private final ComboBox<String> currency=new ComboBox<>();private final ComboBox<TargetCategory> category=new ComboBox<>();
    private final Map<String,CheckBox> fields=new LinkedHashMap<>();private final Map<Integer,CheckBox> destinations=new LinkedHashMap<>();
    private final Map<Integer,CopyOptions> configurations=new LinkedHashMap<>();private final Map<String,ComboBox<TargetAttribute>> mappings=new LinkedHashMap<>();
    private final Map<String,Map<Integer,CopyOptions>> cardConfigurations=new LinkedHashMap<>();
    private final Map<String,TextField> attributeValues=new LinkedHashMap<>();private final Map<String,VariantControls> variantControls=new LinkedHashMap<>();
    private final List<ProductDocument> editedDocuments=new ArrayList<>();private List<Shop> available=List.of();private List<ProductKey> frozen=List.of();
    private ProductSnapshot snapshot;private Shop shop,editing;private CopyJob selectedJob;private List<TargetAttribute> attributes=List.of();
    private long generation,editorGeneration,detailGeneration,attributeGeneration;private boolean loading,disposed,busy,categoriesLoading,attributesLoading;private String editingJobId;private Runnable onChooseProducts;
    private CopyOptions editorBase;
    private String statusKey,statusSuffix="";
    private record VariantControls(CheckBox selected,TextField article,TextField size,TextField russian,ComboBox<TargetAttribute> sizeAttribute,ComboBox<TargetAttribute> russianAttribute,TextField price){}

    public ListingCopyPane(){this(new ListingCopyService(),ShopReadCoordinator.shared());}
    public ListingCopyPane(ListingCopyService service,ShopReadCoordinator reads){
        this.service=service;this.reads=reads;setId("listingCopyPane");getStyleClass().addAll("products-workspace","listing-copy-workspace");setPadding(new Insets(18));
        var heading=label("title");heading.setId("copyTitle");heading.getStyleClass().add("h2");var steps=label("steps");steps.setWrapText(true);setTop(new VBox(8,heading,steps,status));
        sourceCard.setId("copySource");sourceCard.setMaxWidth(Double.MAX_VALUE);sourceCard.setConverter(new StringConverter<>(){public String toString(ProductKey k){return k==null?"":k.cardId();}public ProductKey fromString(String s){return null;}});
        editorShop.setId("copyDestinationEditor");editorShop.setConverter(shopConverter());editorShop.setMaxWidth(Double.MAX_VALUE);
        description.setPrefRowCount(3);description.setWrapText(true);category.setId("copyCategory");category.setMaxWidth(Double.MAX_VALUE);currency.getItems().addAll("RUB","BYN","KZT","EUR","USD","CNY");currency.setValue("RUB");
        title.setId("copyProductTitle");price.setId("copyPrice");article.setId("copyArticle");
        var fieldBar=new FlowPane(10,8);for(String name:List.of("title","description","brand","attributes","images","gtin","sizes","dimensions","documents")){var c=check("field."+name);c.setSelected(true);c.setId("copyField_"+name);if(Set.of("title","sizes","dimensions").contains(name))c.setDisable(true);fields.put(name,c);fieldBar.getChildren().add(c);c.selectedProperty().addListener((o,a,b)->invalidatePreview());}
        generalDetails.setContent(new VBox(8,fieldBar,label("field.title"),title,label("field.brand"),brand,label("field.description"),description,new FlowPane(10,8,label("new_article"),article,label("price"),price,currency),marked));
        generalDetails.setId("copyGeneralDetails");variantDetails.setId("copyVariantDetails");attributeDetails.setId("copyAttributeDetails");documentDetails.setId("copyDocumentDetails");
        variantDetails.setContent(variantBox);attributeDetails.setContent(mappingBox);documentDetails.setContent(documentBox);
        for(var section:List.of(generalDetails,variantDetails,attributeDetails,documentDetails)){section.setAnimated(false);section.setExpanded(false);}
        configuration.getChildren().addAll(label("category"),category,generalDetails,variantDetails,attributeDetails,documentDetails);
        configuration.getStyleClass().add("copy-card");previewBox.getStyleClass().add("copy-card");
        details.setId("copyDetails");details.setContent(configuration);details.setExpanded(false);details.setAnimated(false);details.setText(tr("edit_details"));
        inheritedSummary.setId("copyInheritedSummary");inheritedSummary.setWrapText(true);batchErrors.setId("copyBatchErrors");
        sourceCard.setPrefWidth(240);editorShop.setPrefWidth(280);
        var body=new VBox(10,new FlowPane(10,8,label("source"),sourceCard,choose),inheritedSummary,new VBox(6,label("destinations"),destinationBox),new FlowPane(10,8,label("destination_config"),editorShop),details,batchErrors,previewBox,label("jobs"),jobs);
        jobs.setId("copyJobs");jobs.setPrefHeight(180);jobs.setFixedCellSize(54);jobs.setCellFactory(v->new ListCell<>(){protected void updateItem(CopyJob j,boolean empty){super.updateItem(j,empty);setText(empty||j==null?null:shopName(j.destinationShopId())+" · "+j.source().title()+"\n"+tr("state."+j.state()));}});
        var scroll=new ScrollPane(body);scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);body.setPadding(new Insets(12,0,12,0));setCenter(scroll);
        confirm.setId("copyConfirm");confirm.setWrapText(true);publish.setId("copyPublish");publish.getStyleClass().add("btn-primary");var actions=new FlowPane(10,8,editJob,reconcile,publish);postingProgress.setId("copyPostingProgress");postingProgress.setMaxWidth(Double.MAX_VALUE);setBottom(new VBox(8,postingProgress,actions));
        prepare.setId("copyPrepare");
        sourceCard.valueProperty().addListener((o,a,b)->{if(!loading&&b!=null)loadSource(b);});editorShop.valueProperty().addListener((o,a,b)->{if(!loading&&b!=null){saveEditor();loadEditor(b);}});
        category.valueProperty().addListener((o,a,b)->{if(!loading&&b!=null){invalidatePreview();loadAttributes(b.id());}});
        for(TextInputControl input:List.of(title,brand,description,price,article))input.textProperty().addListener((o,a,b)->invalidatePreview());marked.selectedProperty().addListener((o,a,b)->invalidatePreview());currency.valueProperty().addListener((o,a,b)->invalidatePreview());
        choose.setOnAction(e->{if(onChooseProducts!=null)onChooseProducts.run();});prepare.setOnAction(e->prepare());publish.setOnAction(e->prepare());reconcile.setOnAction(e->reconcile());editJob.setOnAction(e->editSelected());
        confirm.selectedProperty().addListener((o,a,b)->updateButtons());jobs.getSelectionModel().selectedItemProperty().addListener((o,a,b)->loadJob(b));
        following.setOnFinished(e->{var job=selectedJob;if(disposed||busy||job==null)return;followAttempts.merge(job.id(),1,Integer::sum);reconcile();});
        applyTranslations();updateButtons();
    }
    public void setOnChooseProducts(Runnable callback){onChooseProducts=callback;}
    public List<Shop> availableDestinations(){return available;}
    public int currentShopId(){return shop==null?0:shop.getId();}
    public boolean isBusy(){return busy;}
    public void setShop(Shop value){
        if(disposed)return;if(shop!=null&&value!=null&&com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(value)))return;generation++;editorGeneration++;detailGeneration++;attributeGeneration++;following.stop();followAttempts.clear();busy=false;editingJobId=null;shop=value==null?null:copy(value);frozen=List.of();snapshot=null;editing=null;configurations.clear();cardConfigurations.clear();sourceCard.getItems().clear();destinationBox.getChildren().clear();destinations.clear();editorShop.getItems().clear();jobs.getItems().clear();selectedJob=null;editorBase=null;categoriesLoading=false;attributesLoading=false;details.setExpanded(false);batchErrors.getChildren().clear();inheritedSummary.setText("");previewBox.getChildren().clear();variantBox.getChildren().clear();mappingBox.getChildren().clear();documentBox.getChildren().clear();confirm.setSelected(false);setStatus(value==null?"select_shop":"choose_source","");updateButtons();if(value!=null)loadShopsAndJobs();
    }
    public void open(Shop source,List<ProductKey> keys){
        setShop(source);if(source==null)return;for(var k:keys)if(k.shopId()!=source.getId()||k.marketplace()!=source.getMarketplace())throw new SecurityException("Foreign product selection");generation++;busy=false;editingJobId=null;configurations.clear();cardConfigurations.clear();frozen=List.copyOf(keys);var cards=new LinkedHashMap<String,ProductKey>();frozen.forEach(k->cards.putIfAbsent(k.cardId(),k));if(cards.size()>100){setStatus("batch_limit","");frozen=List.of();return;}loading=true;sourceCard.getItems().setAll(cards.values());sourceCard.setValue(sourceCard.getItems().isEmpty()?null:sourceCard.getItems().getFirst());loading=false;if(sourceCard.getValue()!=null)loadSource(sourceCard.getValue());loadShopsAndJobs();
    }
    private void loadShopsAndJobs(){var owner=copy(shop);long g=generation;read(owner,"copy-shops",g,()->new ShopRepository().findAll(),shops->{available=shops.stream().filter(Shop::isCredentialConfigured).toList();destinationBox.getChildren().clear();destinations.clear();for(var s:available){var c=new CheckBox(shopLabel(s));c.setId("copyDestination_"+s.getId());c.setUserData(s);c.selectedProperty().addListener((o,a,b)->{if(loading)return;saveEditor();if(b&&!editorShop.getItems().contains(s))editorShop.getItems().add(s);if(!b)editorShop.getItems().remove(s);if(editorShop.getValue()==null&&!editorShop.getItems().isEmpty())editorShop.setValue(editorShop.getItems().getFirst());invalidatePreview();});destinations.put(s.getId(),c);destinationBox.getChildren().add(c);}loadJobs(owner,g);});}
    private void loadJobs(Shop owner,long g){read(owner,"copy-jobs",g,()->{var merged=new LinkedHashMap<String,CopyJob>();service.list(owner).forEach(j->merged.put(j.id(),j));service.listSource(owner).forEach(j->merged.put(j.id(),j));return merged.values().stream().limit(100).toList();},list->{jobs.getItems().setAll(list);updateButtons();});}
    private void loadJob(CopyJob summary){
        long ticket=++detailGeneration,g=generation;selectedJob=null;showPreview(null);if(summary==null)return;
        var destination=available.stream().filter(s->s.getId()==summary.destinationShopId()).findFirst().orElse(null);if(destination==null)return;var owner=copy(destination);
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"copy-detail",summary.id()+":"+summary.updatedAt()),()->service.get(owner,summary.id())).whenComplete((job,error)->Platform.runLater(()->{
            if(disposed||g!=generation||ticket!=detailGeneration)return;
            if(error!=null){setStatus("read_error","");return;}selectedJob=job;showPreview(job);
        }));
    }
    private void loadSource(ProductKey key){
        saveEditor();if(snapshot!=null)cardConfigurations.put(snapshot.key().cardId(),Map.copyOf(configurations));var owner=copy(shop);long g=++generation;editing=null;configurations.clear();configurations.putAll(cardConfigurations.getOrDefault(key.cardId(),Map.of()));snapshot=null;selectedJob=null;confirm.setSelected(false);previewBox.getChildren().clear();setStatus("loading","");updateButtons();
        read(owner,"copy-source-"+key.cardId(),g,()->service.read(owner,key),value->{snapshot=value;setStatus("source",value.title()+" · "+sourceCard.getItems().size());if(editorShop.getValue()!=null)loadEditor(editorShop.getValue());else updateButtons();});
    }
    private void loadEditor(Shop destination){
        if(snapshot==null)return;
        editing=destination;long ticket=++editorGeneration;attributeGeneration++;
        var owner=copy(destination);long g=generation;loading=true;categoriesLoading=true;attributesLoading=false;
        category.getItems().clear();category.setValue(null);mappingBox.getChildren().clear();mappings.clear();attributeValues.clear();attributes=List.of();editedDocuments.clear();
        var selected=new LinkedHashSet<String>();
        for(var key:frozen)if(key.cardId().equals(snapshot.key().cardId())){
            if(key.variantId().isBlank())snapshot.variants().forEach(v->selected.add(v.id()));else selected.add(key.variantId());
        }
        editorBase=configurations.getOrDefault(destination.getId(),CopyDefaults.initial(snapshot,destination,selected,""));
        var options=editorBase;
        baseline(title,options.textOverrides().getOrDefault("title",snapshot.title()));
        baseline(brand,options.textOverrides().getOrDefault("brand",snapshot.brand()));
        baseline(description,options.textOverrides().getOrDefault("description",snapshot.description()));
        baseline(article,options.newArticles().getOrDefault("*",CopyDefaults.article(snapshot,destination,null)));
        baseline(price,options.targetPrice()!=null?options.targetPrice().toPlainString():CopyDefaults.price(snapshot)==null?"":CopyDefaults.price(snapshot).toPlainString());
        currency.setValue(options.currency());
        marked.setSelected(Boolean.TRUE.equals(options.kizMarked()));marked.setVisible(destination.getMarketplace()==Marketplace.WILDBERRIES);marked.setManaged(marked.isVisible());
        for(var field:fields.entrySet())field.getValue().setSelected(options.fields().contains(field.getKey()));
        editedDocuments.addAll(options.documents());buildVariants(options);buildDocuments();loading=false;invalidatePreview();
        refreshInheritedSummary();
        reads.submit(new ShopReadCoordinator.Key(owner.getId(),"copy-categories-"+ticket,Long.toString(g)),true,()->service.adapter(owner).categories(owner))
            .whenComplete((list,error)->Platform.runLater(()->{
                if(disposed||g!=generation||ticket!=editorGeneration)return;
                categoriesLoading=false;
                if(error!=null){setStatus("read_error",safeCode(error));details.setExpanded(true);updateButtons();return;}
                loading=true;category.getItems().setAll(list);
                var explicit=list.stream().filter(c->c.id().equals(editorBase.targetCategoryId())).findFirst().orElse(null);
                category.setValue(explicit!=null?explicit:CopyDefaults.category(snapshot,owner.getMarketplace(),list));loading=false;
                if(category.getValue()!=null)loadAttributes(category.getValue().id());
                updateButtons();
            }));
    }
    private static void baseline(TextInputControl control,String value){control.setText(Objects.toString(value,""));control.getProperties().put("copy.baseline",control.getText());}
    private static boolean changed(TextInputControl control){return !Objects.equals(control.getProperties().get("copy.baseline"),control.getText());}
    private static void baseline(ComboBox<TargetAttribute> control){control.getProperties().put("copy.baseline",control.getValue()==null?"":control.getValue().id());}
    private static boolean changed(ComboBox<TargetAttribute> control){return !Objects.equals(control.getProperties().get("copy.baseline"),control.getValue()==null?"":control.getValue().id());}
    private static String safeCode(Throwable error){return com.vncode.app.features.sync.SyncFailure.of(error,0).code();}

    private void buildVariants(CopyOptions options){
        variantBox.getChildren().clear();variantControls.clear();var allowed=new HashSet<String>();
        for(var key:frozen)if(key.cardId().equals(snapshot.key().cardId())){if(key.variantId().isBlank())snapshot.variants().forEach(v->allowed.add(v.id()));else allowed.add(key.variantId());}
        for(var variant:snapshot.variants())if(allowed.contains(variant.id())){
            String identity=variant.id()+" · "+i18n.tr("products.barcode")+": "+String.join(", ",CopyDefaults.barcodes(snapshot,variant));
            if(variant.gtinVerified())identity+=" · GTIN: "+variant.gtin();
            var check=new CheckBox(identity);check.setSelected(options.variantIds().contains(variant.id()));
            var newArticle=new TextField();baseline(newArticle,options.newArticles().getOrDefault(variant.id(),CopyDefaults.article(snapshot,editing,variant)));newArticle.setPrefWidth(180);
            var size=new TextField();baseline(size,options.variantValues().getOrDefault(variant.id(),Map.of()).getOrDefault("@size",List.of(Objects.toString(variant.size(),""))).getFirst());size.setPrefWidth(80);size.setId("copyVariantSize_"+variant.id());
            var ru=new TextField();baseline(ru,options.variantValues().getOrDefault(variant.id(),Map.of()).getOrDefault("@russianSize",List.of(Objects.toString(variant.russianSize(),""))).getFirst());ru.setPrefWidth(80);
            var amount=new TextField();var inherited=CopyDefaults.targetPrice(snapshot,options,variant);baseline(amount,inherited==null?"":inherited.toPlainString());amount.setPrefWidth(100);amount.setId("copyVariantPrice_"+variant.id());
            var targetSize=new ComboBox<TargetAttribute>();targetSize.setPrefWidth(190);var targetRussian=new ComboBox<TargetAttribute>();targetRussian.setPrefWidth(190);targetSize.setId("copySizeAttribute_"+variant.id());targetRussian.setId("copyRussianSizeAttribute_"+variant.id());baseline(targetSize);baseline(targetRussian);
            var row=new FlowPane(8,6,check,label("size"),size,label("russian_size"),ru,label("variant_price"),amount);
            if(editing.getMarketplace()==Marketplace.OZON)row.getChildren().addAll(newArticle,targetSize,targetRussian);
            variantBox.getChildren().add(row);variantControls.put(variant.id(),new VariantControls(check,newArticle,size,ru,targetSize,targetRussian,amount));
            for(var input:List.of(size,ru,newArticle,amount))input.textProperty().addListener((o,a,b)->invalidatePreview());
            check.selectedProperty().addListener((o,a,b)->invalidatePreview());targetSize.valueProperty().addListener((o,a,b)->invalidatePreview());targetRussian.valueProperty().addListener((o,a,b)->invalidatePreview());
        }
    }

    private void loadAttributes(String id){
        if(editing==null||snapshot==null)return;
        // Persist dirty controls before an asynchronous dictionary read rebuilds them.
        if(!attributesLoading)saveEditor();
        var owner=copy(editing);long g=generation,editorTicket=editorGeneration,ticket=++attributeGeneration;
        var options=configurations.getOrDefault(owner.getId(),editorBase);
        editorBase=options;attributesLoading=true;mappingBox.getChildren().clear();mappings.clear();attributeValues.clear();updateButtons();
        reads.submit(new ShopReadCoordinator.Key(owner.getId(),"copy-attributes-"+id+"-"+ticket,Long.toString(g)),true,()->service.adapter(owner).attributes(owner,id))
            .whenComplete((list,error)->Platform.runLater(()->{
                if(disposed||g!=generation||editorTicket!=editorGeneration||ticket!=attributeGeneration||category.getValue()==null||!category.getValue().id().equals(id))return;
                attributesLoading=false;
                if(error!=null){setStatus("read_error",safeCode(error));details.setExpanded(true);updateButtons();return;}
                attributes=List.copyOf(list);loading=true;editorBase=options;
                var inherited=CopyDefaults.complete(snapshot,owner,options,list);
                var keys=new HashSet<String>();
                for(var attribute:snapshot.attributes())if(keys.add(attribute.mappingKey()))addMapping(attribute.mappingKey(),attribute.name()+" · "+String.join(", ",attribute.values()),list,inherited);
                if(owner.getMarketplace()==Marketplace.OZON){addMapping("@brand",tr("field.brand"),list,inherited);addMapping("@description",tr("field.description"),list,inherited);}
                for(var attribute:list){
                    var defaults=new ArrayList<String>();
                    if(attribute.complexId().isBlank()||attribute.complexId().equals("0"))for(var source:snapshot.attributes())if(attribute.id().equals(inherited.attributeMapping().get(source.mappingKey())))defaults.addAll(source.values());
                    var input=new TextField();baseline(input,String.join(" | ",inherited.attributeValues().getOrDefault(attribute.id(),defaults)));input.setId("copyAttributeValue_"+attribute.id());input.setPromptText(tr("values_hint"));input.textProperty().addListener((o,x,y)->invalidatePreview());
                    attributeValues.put(attribute.id(),input);mappingBox.getChildren().add(new VBox(4,new Label(attribute.toString()),input));
                }
                for(var entry:variantControls.entrySet()){
                    var controls=entry.getValue();var size=variantAttribute(options,entry.getKey(),"size",list);var ru=variantAttribute(options,entry.getKey(),"russianSize",list);
                    controls.sizeAttribute().getItems().setAll(list);controls.russianAttribute().getItems().setAll(list);
                    controls.sizeAttribute().setValue(size);controls.russianAttribute().setValue(ru);baseline(controls.sizeAttribute());baseline(controls.russianAttribute());
                    var values=options.variantValues().getOrDefault(entry.getKey(),Map.of());
                    if(size!=null&&values.containsKey(size.id())&&!changed(controls.size()))baseline(controls.size(),String.join(" | ",values.get(size.id())));
                    if(ru!=null&&values.containsKey(ru.id())&&!changed(controls.russian()))baseline(controls.russian(),String.join(" | ",values.get(ru.id())));
                }
                loading=false;updateButtons();
            }));
    }

    private void addMapping(String id,String name,List<TargetAttribute> list,CopyOptions options){var combo=new ComboBox<TargetAttribute>();combo.setId("copyMapping_"+id);combo.getItems().add(null);combo.getItems().addAll(list);if(!id.startsWith("@")){combo.getItems().add(new TargetAttribute("@skip",tr("omit_attribute"),"",false,"",0,"","",false));if(editing.getMarketplace()==Marketplace.WILDBERRIES)for(String field:List.of("brand","description","title","size","russianSize"))combo.getItems().add(new TargetAttribute("@"+field,(field.equals("size")?tr("size"):field.equals("russianSize")?tr("russian_size"):tr("field."+field)),"",false,"",0,"","",true));}combo.setMaxWidth(Double.MAX_VALUE);if(options!=null){String target=options.attributeMapping().get(id);combo.setValue(combo.getItems().stream().filter(a->a!=null&&a.id().equals(target)).findFirst().orElse(null));}baseline(combo);combo.valueProperty().addListener((o,a,b)->invalidatePreview());mappings.put(id,combo);var label=id.startsWith("@")?label("field."+id.substring(1)):new Label(name);label.setWrapText(true);mappingBox.getChildren().add(new VBox(4,label,combo));}
    private void buildDocuments(){documentBox.getChildren().clear();for(int index=0;index<editedDocuments.size();index++){int i=index;var d=editedDocuments.get(i);var text=new Label(d.type()+" · "+d.number()+" · "+tr(d.file()==null?"file_missing":"file_attached"));text.setWrapText(true);var edit=button("edit_document");edit.setOnAction(e->editDocument(i));documentBox.getChildren().add(new FlowPane(8,6,text,edit));}var add=button("add_document");add.setOnAction(e->editDocument(-1));documentBox.getChildren().add(add);}
    private void editDocument(int index){
        if(editing==null)return;var old=index<0?null:editedDocuments.get(index);var dialog=new Dialog<ProductDocument>();dialog.setTitle(tr("edit_document"));var form=new VBox(8);var type=new TextField(old==null?"":old.type());var number=new TextField(old==null?"":old.number());var owner=new TextField(old==null?"":old.owner());var from=new DatePicker(old==null?null:old.validFrom());var to=new DatePicker(old==null?null:old.validUntil());var metadata=new LinkedHashMap<String,TextField>();
        var meta=old==null?Map.<String,String>of():old.metadata();for(String key:editing.getMarketplace()==Marketplace.WILDBERRIES?List.of("productNumber","tradeName","applicant","isEndless"):List.of("certificate_type","certificate_country","accordance_type","name","product_type","link_to_registry","isEndless")){var input=new TextField(meta.getOrDefault(key,""));metadata.put(key,input);form.getChildren().addAll(label("doc."+key),input);}
        var file=new java.util.concurrent.atomic.AtomicReference<java.net.URI>(old==null?null:old.file());var fileLabel=new Label(file.get()==null?tr("file_missing"):Path.of(file.get()).getFileName().toString());var select=button("choose_file");select.setOnAction(e->{var picker=new FileChooser();picker.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("documents"),"*.pdf","*.jpg","*.jpeg","*.png"));var chosen=picker.showOpenDialog(getScene()==null?null:getScene().getWindow());if(chosen!=null){file.set(chosen.toPath().toUri());fileLabel.setText(chosen.getName());}});
        form.getChildren().addAll(label("doc.type"),type,label("doc.number"),number,label("doc.owner"),owner,label("doc.from"),from,label("doc.to"),to,fileLabel,select);var scroll=new ScrollPane(form);scroll.setFitToWidth(true);scroll.setPrefSize(460,440);dialog.getDialogPane().setContent(scroll);var save=new ButtonType(tr("save"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);dialog.setResultConverter(b->{if(b!=save)return null;var values=new LinkedHashMap<>(meta);metadata.forEach((k,v)->{if(!v.getText().isBlank())values.put(k,v.getText());});if(editing.getMarketplace()==Marketplace.OZON&&!values.containsKey("certificate_type"))values.put("certificate_type",type.getText());return new ProductDocument(old==null?"":old.id(),type.getText(),number.getText(),from.getValue(),to.getValue(),owner.getText(),"",file.get(),old==null?editing.getMarketplace():old.marketplace(),DocumentState.UNVERIFIED,"",null,values);});try{dialog.showAndWait().ifPresent(d->{if(index<0)editedDocuments.add(d);else editedDocuments.set(index,d);buildDocuments();invalidatePreview();});}catch(IllegalArgumentException invalid){setStatus("invalid_data","");}
    }
    private CopyOptions editorOptions(){
        if(snapshot==null||editing==null||editorBase==null)throw new IllegalStateException("No destination editor");
        var base=editorBase;var text=new LinkedHashMap<>(base.textOverrides());var selectedFields=new LinkedHashSet<String>();fields.forEach((key,control)->{if(control.isSelected())selectedFields.add(key);});
        var variants=new LinkedHashSet<String>();var articles=new LinkedHashMap<>(base.newArticles());var valuesByVariant=new LinkedHashMap<>(base.variantValues());var prices=new LinkedHashMap<>(base.variantPrices());
        if(changed(article))articles.put("*",article.getText());
        for(var entry:variantControls.entrySet())if(entry.getValue().selected().isSelected()){
            String id=entry.getKey();var controls=entry.getValue();variants.add(id);
            if(changed(controls.article()))articles.put(id,controls.article().getText());
            var values=new LinkedHashMap<>(base.variantValues().getOrDefault(id,Map.of()));
            if(editing.getMarketplace()==Marketplace.WILDBERRIES){if(changed(controls.size()))values.put("@size",List.of(controls.size().getText()));if(changed(controls.russian()))values.put("@russianSize",List.of(controls.russian().getText()));}
            else{storeVariantValue(values,controls.sizeAttribute(),controls.size());storeVariantValue(values,controls.russianAttribute(),controls.russian());if(changed(controls.sizeAttribute()))text.put("@sizeAttribute."+id,controls.sizeAttribute().getValue()==null?"":controls.sizeAttribute().getValue().id());if(changed(controls.russianAttribute()))text.put("@russianSizeAttribute."+id,controls.russianAttribute().getValue()==null?"":controls.russianAttribute().getValue().id());}
            valuesByVariant.put(id,values);
            if(changed(controls.price())){if(controls.price().getText().isBlank())prices.remove(id);else prices.put(id,new BigDecimal(controls.price().getText().strip()));}
        }
        var maps=new LinkedHashMap<>(base.attributeMapping());mappings.forEach((id,control)->{if(changed(control))maps.put(id,control.getValue()==null?"":control.getValue().id());});
        var values=new LinkedHashMap<>(base.attributeValues());attributeValues.forEach((id,input)->{if(changed(input))values.put(id,input.getText().isBlank()?List.of():Arrays.stream(input.getText().split("\\s*\\|\\s*")).toList());});
        for(var field:Map.of("title",title,"brand",brand,"description",description).entrySet())if(changed(field.getValue()))text.put(field.getKey(),field.getValue().getText());
        var amount=changed(price)?price.getText().isBlank()?null:new BigDecimal(price.getText().strip()):base.targetPrice();
        return new CopyOptions(selectedFields,variants,category.getValue()==null?base.targetCategoryId():category.getValue().id(),maps,values,articles,amount,currency.getValue(),fields.get("documents").isSelected()?List.copyOf(editedDocuments):List.of(),editing.getMarketplace()==Marketplace.WILDBERRIES?marked.isSelected():null,valuesByVariant,text,prices);
    }
    private static TargetAttribute variantAttribute(CopyOptions options,String variant,String role,List<TargetAttribute> dictionary){String explicit=options.textOverrides().get("@"+role+"Attribute."+variant);return explicit==null?CopyDefaults.match(role,dictionary):dictionary.stream().filter(a->a.id().equals(explicit)).findFirst().orElse(null);}
    private static void storeVariantValue(Map<String,List<String>> values,ComboBox<TargetAttribute> attribute,TextField input){
        if(!changed(attribute)&&!changed(input))return;
        if(changed(attribute)){Object prior=attribute.getProperties().get("copy.baseline");if(prior instanceof String id&&!id.isBlank())values.remove(id);}
        if(attribute.getValue()!=null)values.put(attribute.getValue().id(),List.of(input.getText()));
        else if(!input.getText().isBlank())values.put("@unmappedSize",List.of(input.getText()));
    }

    private void saveEditor(){if(editing==null||snapshot==null||loading)return;try{configurations.put(editing.getId(),editorOptions());}catch(RuntimeException invalid){/* Keep unsaved inputs visible; prepare reports invalid data. */}}
    private record Preparation(List<CopyJob> jobs,List<String> failures){}
    private void prepare(){
        if(shop==null||snapshot==null||editing==null||busy||categoriesLoading||attributesLoading)return;
        try{
            saveEditor();configurations.put(editing.getId(),editorOptions());cardConfigurations.put(snapshot.key().cardId(),Map.copyOf(configurations));
            var owners=available.stream().filter(s->destinations.get(s.getId())!=null&&destinations.get(s.getId()).isSelected()).map(ListingCopyPane::copy).toList();
            if(editingJobId!=null)owners=List.of(copy(editing));
            if(owners.isEmpty()){setStatus("destination_required","");return;}
            var groups=new LinkedHashMap<String,List<ProductKey>>();for(var key:frozen)groups.computeIfAbsent(key.cardId(),k->new ArrayList<>()).add(key);
            String sourceCategory=snapshot.categoryId();var hints=new HashMap<Integer,String>();configurations.forEach((id,o)->hints.put(id,o.targetCategoryId()));
            var source=copy(shop);var options=Map.copyOf(cardConfigurations);var targets=List.copyOf(owners);String editId=editingJobId;long g=generation;
            busy=true;selectedJob=null;detailGeneration++;confirm.setSelected(false);batchErrors.getChildren().clear();setStatus("preparing","");postingProgress.setProgress(0);updateButtons();
            var work=reads.submit(new ShopReadCoordinator.Key(source.getId(),"copy-prepare",Long.toString(g)),true,()->{
                var result=new ArrayList<CopyJob>();var failures=new ArrayList<String>();int count=0,total=groups.size()*targets.size();
                for(var group:groups.entrySet())for(var destination:targets){
                    int current=++count;Platform.runLater(()->{if(!disposed&&g==generation){postingProgress.setProgress((current-1.0)/total);setStatus("sending",current+"/"+total+" · "+shopLabel(destination)+" · "+group.getKey());}});
                    CopyJob draft=null;
                    try{
                        var requested=options.getOrDefault(group.getKey(),Map.of()).get(destination.getId());
                        if(requested==null){
                            var fresh=service.read(source,group.getValue().getFirst());var selected=new LinkedHashSet<String>();
                            for(var key:group.getValue())if(key.variantId().isBlank())fresh.variants().forEach(v->selected.add(v.id()));else selected.add(key.variantId());
                            requested=CopyDefaults.initial(fresh,destination,selected,fresh.categoryId().equals(sourceCategory)?hints.getOrDefault(destination.getId(),""):"");
                        }
                        draft=editId==null?service.draft(source,group.getValue(),List.of(destination),requested).getFirst():service.configure(destination,editId,requested);
                        service.preview(destination,draft.id());
                        var checked=service.get(destination,draft.id());
                        if(checked.state()==CopyState.AWAITING_CONFIRMATION&&checked.preview()!=null&&checked.preview().publishable()){
                            var accepted=checked;
                            checked=SellerMutationExecutor.submit(destination,()->service.publish(destination,accepted.id(),accepted.preview().requestHash(),true)).get();
                        }
                        if(checked.state()==CopyState.REMOTE_PROCESSING||checked.state()==CopyState.RECONCILE_REQUIRED){
                            var sent=checked;checked=SellerMutationExecutor.submit(destination,()->service.reconcile(destination,sent.id())).get();
                        }
                        result.add(checked.summary());
                    }catch(Exception error){
                        failures.add(shopLabel(destination)+" · "+group.getKey()+" · "+safeCode(error));
                        if(draft!=null)try{result.add(service.get(destination,draft.id()).summary());}catch(RuntimeException changedOwner){/* Report without resurrecting a stale owner. */}
                        if(Thread.currentThread().isInterrupted())throw error;
                    }
                    int completed=current;Platform.runLater(()->{if(!disposed&&g==generation)postingProgress.setProgress((double)completed/total);});
                }
                return new Preparation(List.copyOf(result),List.copyOf(failures));
            });
            work.whenComplete((result,error)->Platform.runLater(()->{
                if(disposed||g!=generation)return;busy=false;
                if(error!=null)setStatus("read_error",safeCode(error));
                else{
                    jobs.getItems().setAll(result.jobs());
                    for(String failure:result.failures()){var label=new Label(failure);label.setWrapText(true);label.getStyleClass().add("text-danger");batchErrors.getChildren().add(label);}
                    jobs.getSelectionModel().selectFirst();setStatus(result.failures().isEmpty()?"posted":"batch_partial",result.jobs().size()+"/"+(groups.size()*targets.size()));
                }
                updateButtons();
            }));
        }catch(RuntimeException invalid){setStatus("invalid_data","");details.setExpanded(true);busy=false;updateButtons();}
    }

    private void showPreview(CopyJob job){
        following.stop();confirm.setSelected(false);previewBox.getChildren().clear();
        if(job!=null){
            var heading=new Label(shopName(job.destinationShopId())+" · "+job.source().title()+" · "+tr("state."+job.state()));heading.setWrapText(true);previewBox.getChildren().add(heading);
            for(var issue:job.issues()){
                var label=new Label(issueText(issue));label.setWrapText(true);label.getStyleClass().add(issue.blocking()?"text-danger":"text-muted");previewBox.getChildren().add(label);
                if(issue.blocking()&&snapshot!=null&&editing!=null&&editing.getId()==job.destinationShopId()&&snapshot.key().cardId().equals(job.source().key().cardId())){
                    details.setExpanded(true);String code=issue.code();
                    if(code.contains("DOCUMENT"))documentDetails.setExpanded(true);
                    else if(code.contains("ATTRIBUTE")||code.contains("DICTIONARY")||code.contains("MAPPING"))attributeDetails.setExpanded(true);
                    else if(code.contains("PRICE")||code.contains("VARIANT")||code.contains("GTIN")||code.contains("SIZE")||code.contains("ARTICLE"))variantDetails.setExpanded(true);
                    else if(!code.contains("CATEGORY"))generalDetails.setExpanded(true);
                }
            }
            if((job.state()==CopyState.REMOTE_PROCESSING||job.state()==CopyState.RECONCILE_REQUIRED)&&followAttempts.getOrDefault(job.id(),0)<12)following.playFromStart();
            else if(job.state()==CopyState.REMOTE_PROCESSING||job.state()==CopyState.RECONCILE_REQUIRED)previewBox.getChildren().add(label("follow_paused"));
        }
        updateButtons();
    }

    private void editSelected(){
        var job=selectedJob;if(job==null||submitted(job.state()))return;
        var source=available.stream().filter(s->s.getId()==job.sourceShopId()).findFirst().orElse(null);var destination=available.stream().filter(s->s.getId()==job.destinationShopId()).findFirst().orElse(null);if(source==null||destination==null)return;
        saveEditor();generation++;editorGeneration++;attributeGeneration++;following.stop();followAttempts.clear();busy=false;shop=copy(source);snapshot=job.source();editingJobId=job.id();selectedJob=null;
        frozen=job.options().variantIds().stream().map(id->new ProductKey(source.getId(),source.getMarketplace(),job.source().key().cardId(),id)).toList();
        configurations.clear();cardConfigurations.clear();configurations.put(destination.getId(),job.options());
        loading=true;sourceCard.getItems().setAll(job.source().key());sourceCard.setValue(job.source().key());
        destinations.forEach((id,box)->box.setSelected(id==destination.getId()));editorShop.getItems().setAll(destination);editorShop.setValue(destination);loading=false;
        details.setExpanded(true);loadEditor(destination);
    }

    private void reconcile(){var j=selectedJob;if(j==null||!submitted(j.state())||busy)return;var owner=available.stream().filter(s->s.getId()==j.destinationShopId()).findFirst().orElse(null);if(owner!=null)mutate(copy(owner),j,()->service.reconcile(copy(owner),j.id()));}
    private void mutate(Shop owner,CopyJob job,Callable<CopyJob> action){long g=generation;busy=true;confirm.setSelected(false);updateButtons();setStatus("sending","");SellerMutationExecutor.submit(action).whenComplete((result,error)->Platform.runLater(()->{if(disposed||g!=generation)return;busy=false;if(error!=null){status.setText((rejected(error)?i18n.tr("runtime.queue_full"):tr("reconcile_required"))+" · "+safeCode(error));showPreview(selectedJob);}else{int index=-1;for(int i=0;i<jobs.getItems().size();i++)if(jobs.getItems().get(i).id().equals(job.id()))index=i;var brief=result.summary();if(index>=0)jobs.getItems().set(index,brief);else jobs.getItems().add(brief);jobs.getSelectionModel().select(brief);selectedJob=result;showPreview(result);setStatus("state."+result.state(),shopLabel(owner));}updateButtons();}));}
    private static boolean rejected(Throwable error){return error instanceof RejectedExecutionException||error.getCause() instanceof RejectedExecutionException;}
    private <T> void read(Shop owner,String workload,long g,Callable<T> task,Consumer<T> apply){(workload.equals("copy-shops")||workload.equals("copy-jobs")?reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),workload,Long.toString(g)),task):reads.submit(new ShopReadCoordinator.Key(owner.getId(),workload,Long.toString(g)),true,task)).whenComplete((value,error)->Platform.runLater(()->{if(disposed||g!=generation)return;if(error!=null)setStatus("read_error",safeCode(error));else apply.accept(value);updateButtons();}));}
    private void invalidatePreview(){if(loading)return;detailGeneration++;following.stop();selectedJob=null;confirm.setSelected(false);updateButtons();}
    private void updateButtons(){sourceCard.setDisable(busy);editorShop.setDisable(busy);destinationBox.setDisable(busy);configuration.setDisable(busy||categoriesLoading||attributesLoading);details.setVisible(snapshot!=null&&editing!=null);details.setManaged(details.isVisible());configuration.setVisible(snapshot!=null&&editing!=null);configuration.setManaged(configuration.isVisible());boolean ready=selectedJob!=null&&selectedJob.state()==CopyState.AWAITING_CONFIRMATION&&selectedJob.preview()!=null&&selectedJob.preview().publishable();publish.setDisable(busy||snapshot==null||editing==null||categoriesLoading||attributesLoading||destinations.values().stream().noneMatch(CheckBox::isSelected));reconcile.setDisable(busy||selectedJob==null||!submitted(selectedJob.state())||selectedJob.state()==CopyState.RECONCILED);editJob.setDisable(busy||selectedJob==null||submitted(selectedJob.state()));prepare.setDisable(busy||snapshot==null||editing==null||categoriesLoading||attributesLoading);choose.setDisable(shop==null||busy);}
    private void setStatus(String key,String suffix){statusKey=key;statusSuffix=suffix;status.setText(tr(key)+(suffix.isBlank()?"":" · "+suffix));}
    private void refreshInheritedSummary(){if(snapshot!=null)inheritedSummary.setText(snapshot.title()+" · "+variantControls.size()+" "+tr("source_variant")+" · "+tr("inherited_hint"));}
    public void applyTranslations(){if(statusKey!=null)setStatus(statusKey,statusSuffix);refreshInheritedSummary();details.setText(tr("edit_details"));generalDetails.setText(tr("general_details"));variantDetails.setText(tr("variants"));attributeDetails.setText(tr("attributes"));documentDetails.setText(tr("documents"));labels.forEach((node,key)->node.setText(tr(key)));publish.setText(tr("post_now"));if(jobs!=null)jobs.refresh();if(selectedJob!=null)showPreview(selectedJob);}
    public void dispose(){following.stop();disposed=true;generation++;editorGeneration++;thumbnails.close();/* Sent jobs continue on WRITES; no mutation cancellation. */}
    private Label label(String key){var l=new Label(tr(key));labels.put(l,key);return l;}private Button button(String key){var b=new Button(tr(key));labels.put(b,key);return b;}private CheckBox check(String key){var c=new CheckBox(tr(key));labels.put(c,key);return c;}private String tr(String key){return i18n.tr("copy."+key);}
    private String issueText(CopyIssue issue){String key="copy.issue."+issue.code();return i18n.tr(key,i18n.tr("copy.issue.generic"))+" · "+Objects.toString(issue.field(),"");}
    private String shopName(int id){return available.stream().filter(s->s.getId()==id).map(ListingCopyPane::shopLabel).findFirst().orElse("Shop "+id);}
    private static String shopLabel(Shop s){return "["+(s.getMarketplace()==Marketplace.OZON?"Ozon":"WB")+"] "+s.getName();}private static StringConverter<Shop> shopConverter(){return new StringConverter<>(){public String toString(Shop s){return s==null?"":shopLabel(s);}public Shop fromString(String v){return null;}};}
    private static Shop copy(Shop s){return new Shop(s.getId(),s.getName(),s.getMarketplace(),s.getClientId(),s.getApiKey());}
    private static boolean submitted(CopyState s){return Set.of(CopyState.SENDING,CopyState.REMOTE_PROCESSING,CopyState.RECONCILE_REQUIRED,CopyState.RECONCILED,CopyState.FAILED).contains(s);}
}
