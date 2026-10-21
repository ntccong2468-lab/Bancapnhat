package com.vncode.app.ui.products;

import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.kordamp.ikonli.javafx.FontIcon;

/** Virtualized shop-scoped products. Every result is checked against its owner and generation. */
public final class ProductsPane extends BorderPane {
    private final ProductRepository repository=new ProductRepository();
    private final Function<ProductQuery,ProductPage> reader;
    private final ShopReadCoordinator reads;
    private final ThumbnailLoader thumbnails=new ThumbnailLoader();
    private final ProductRefreshService refreshService;
    private final ProductSelection selection=new ProductSelection();
    private final I18nService i18n=I18nService.getInstance();
    private final ListView<ProductRow> rows=new ListView<>();
    private final StackPane listing=new StackPane(rows);
    private final Map<String,ProductBulkEditPane> backgroundEdits=new HashMap<>();
    private ProductBulkEditPane currentEdit;
    private HBox progressPill;
    private javafx.beans.value.ChangeListener<Boolean> editListener;
    private final javafx.animation.PauseTransition progressDismiss=new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
    void trackBulkEdit(ProductBulkEditPane editor){
        backgroundEdits.put(shopScope,editor);installProgress(editor);
    }
    private void installProgress(ProductBulkEditPane editor){
        progressDismiss.stop();
        if(currentEdit!=null&&editListener!=null)currentEdit.backgroundRunning().removeListener(editListener);
        if(progressPill!=null){for(var node:progressPill.getChildren())if(node instanceof Label label)label.textProperty().unbind();listing.getChildren().remove(progressPill);}
        currentEdit=editor;if(editor==null){updateButtons();return;}
        var text=new Label();text.textProperty().bind(editor.progressText());text.setWrapText(true);text.setMinWidth(0);text.maxWidthProperty().bind(javafx.beans.binding.Bindings.max(160,listing.widthProperty().subtract(160)));
        var spinner=new ProgressIndicator();spinner.setMaxSize(20,20);spinner.visibleProperty().bind(editor.backgroundRunning());spinner.managedProperty().bind(spinner.visibleProperty());
        var details=new Button(tr("products.details"));details.setOnAction(e->editor.showBackgroundDetails());
        progressPill=new HBox(10,spinner,text,details);progressPill.setId("productBulkProgressPill");progressPill.getStyleClass().add("task-status-pill");progressPill.setAlignment(javafx.geometry.Pos.CENTER);progressPill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);
        StackPane.setAlignment(progressPill,javafx.geometry.Pos.BOTTOM_CENTER);StackPane.setMargin(progressPill,new Insets(12));listing.getChildren().add(progressPill);
        var installedPill=progressPill;
        Runnable dismiss=()->{progressDismiss.setOnFinished(e->{if(currentEdit==editor&&!editor.backgroundRunning().get()){listing.getChildren().remove(installedPill);}});progressDismiss.playFromStart();};
        editListener=(o,old,value)->{updateButtons();if(value)progressDismiss.stop();else dismiss.run();};editor.backgroundRunning().addListener(editListener);if(!editor.backgroundRunning().get())dismiss.run();updateButtons();
    }
    private final TextField search=new TextField();
    private final ComboBox<String> category=new ComboBox<>();
    private final ComboBox<DocumentState> document=new ComboBox<>();
    private final ComboBox<ProductIssueFilter> issue=new ComboBox<>();
    private Map<ProductIssueFilter,Long> issueCounts=Map.of();
    private final CheckBox missing=new CheckBox(),pageSelection=new CheckBox();
    private final Label title=new Label(),count=new Label(),status=new Label(),selected=new Label(),needsFix=new Label();
    private final Button reload=new Button(),more=new Button(),all=new Button(),copy=new Button(),clear=new Button("×");
    private final BorderPane bulk=new BorderPane();
    private final FlowPane bulkActions=new FlowPane(12,8),issueBar=new FlowPane(10,6);
    private final Map<ProductIssueFilter,ToggleButton> issueChips=new EnumMap<>(ProductIssueFilter.class);
    private final PauseTransition debounce=new PauseTransition(Duration.millis(300));
    private Consumer<List<ProductKey>> onCopy;
    private boolean pickerMode;
    public List<ProductKey> selectedKeys(){return selection.freeze();}
    public void useAsPicker(){pickerMode=true;copy.setVisible(false);copy.setManaged(false);updateSelection();}
    private BiConsumer<String,List<ProductKey>> onEdit;
    private ProductRefreshService.Report lastSync;
    private Shop shop;private long generation,syncTicket,displayedTotal;private String cursor="",shopScope="";private ProductQuery displayedQuery;private boolean updating,disposed,syncing,loading,selecting;
    public ProductsPane(){this(null,ShopReadCoordinator.shared());}
    public ProductsPane(Function<ProductQuery,ProductPage> reader,ShopReadCoordinator reads) {
        this(reader,reads,new ProductRefreshService());
    }
    public ProductsPane(Function<ProductQuery,ProductPage> reader,ShopReadCoordinator reads,ProductRefreshService refreshService) {
        this.reader=reader==null?repository::loadProductPage:reader;this.reads=Objects.requireNonNull(reads);
        this.refreshService=Objects.requireNonNull(refreshService);reload.setId("productSync");status.setId("productStatus");
        setId("productsPane");getStyleClass().add("products-workspace");setPadding(new Insets(20,8,6,8));
        rows.setId("productRows");rows.setFixedCellSize(135);rows.setCellFactory(list->new ProductCell());
        rows.skinProperty().addListener((o,a,b)->Platform.runLater(this::installScrollPaging));
        var headingSpacer=new Region();HBox.setHgrow(headingSpacer,Priority.ALWAYS);
        var heading=new HBox(12,title,count,headingSpacer,reload);heading.setAlignment(Pos.CENTER_LEFT);heading.setMinHeight(42);
        title.getStyleClass().add("h2");count.getStyleClass().add("text-muted");reload.setGraphic(new FontIcon("fth-refresh-cw"));
        search.setId("productSearch");search.setMinWidth(180);search.setPrefWidth(375);
        category.setPrefWidth(148);document.setPrefWidth(263);
        category.setId("productCategoryFilter");document.setId("productDocumentFilter");
        category.setConverter(new StringConverter<>(){public String toString(String value){return value==null?tr("products.categories"):value;}public String fromString(String value){return value;}});
        document.getItems().add(null);document.getItems().addAll(DocumentState.values());
        document.setConverter(new StringConverter<>(){public String toString(DocumentState value){return value==null?tr("products.documents_all"):doc(value);}public DocumentState fromString(String value){return null;}});
        issue.setId("productIssueFilter");issue.getItems().setAll(ProductIssueFilter.values());issue.setValue(ProductIssueFilter.ALL);issue.setPrefWidth(250);
        issue.setConverter(new StringConverter<>(){public String toString(ProductIssueFilter v){return v==null?"":tr("products.issue."+v.name())+(issueCounts.containsKey(v)?" ("+issueCounts.get(v)+")":"");}public ProductIssueFilter fromString(String v){return ProductIssueFilter.ALL;}});
        pageSelection.setId("productSelectAll");
        var filters=new FlowPane(14,8,pageSelection,search,category,document);filters.setId("productFilters");filters.setAlignment(Pos.CENTER_LEFT);
        needsFix.getStyleClass().add("text-muted");issueBar.getChildren().add(needsFix);issueBar.setAlignment(Pos.CENTER_LEFT);
        for(var kind:List.of(ProductIssueFilter.INVALID_TNVED,ProductIssueFilter.MISSING_BRAND,ProductIssueFilter.MISSING_MATERIAL)){
            var chip=new ToggleButton();chip.setId("productIssue_"+kind.name());chip.getStyleClass().add("product-issue-chip");chip.setOnAction(e->issue.setValue(issue.getValue()==kind?ProductIssueFilter.ALL:kind));issueChips.put(kind,chip);issueBar.getChildren().add(chip);
        }
        status.setWrapText(true);status.managedProperty().bind(status.textProperty().isNotEmpty());status.visibleProperty().bind(status.managedProperty());
        var top=new VBox(14,heading,filters,issueBar,status);top.setPadding(new Insets(0,0,16,0));setTop(top);setCenter(listing);
        bulk.setId("productBulkActions");bulk.getStyleClass().add("products-bulk");
        bulk.setLeft(selected);bulk.setCenter(bulkActions);bulk.setRight(clear);
        BorderPane.setAlignment(selected,Pos.CENTER_LEFT);BorderPane.setAlignment(clear,Pos.CENTER_RIGHT);
        BorderPane.setMargin(bulkActions,new Insets(0,12,0,12));
        selected.getStyleClass().add("text-bold");selected.setMinWidth(Region.USE_PREF_SIZE);bulkActions.setMinWidth(0);bulkActions.setAlignment(Pos.CENTER_LEFT);
        // BorderPane measures the horizontal FlowPane with its actual available
        // width. Binding the preferred wrap width to the previous child width
        // could reserve several rows on the first visible Windows layout pulse.
        for(String field:List.of("brand","material","gender","tnved","documents")) {
            var button=new Button();button.setId("productEdit_"+field);button.getStyleClass().add("btn-primary");button.setDisable(true);button.setUserData(field);
            button.setGraphic(new FontIcon(switch(field){case "brand"->"fth-award";case "material"->"fth-layers";case "gender"->"fth-users";case "tnved"->"fth-hash";default->"fth-shield";}));
            button.setOnAction(event->{var frozen=selection.freeze();if(onEdit!=null&&!frozen.isEmpty()&&!loading&&!syncing&&!selecting)onEdit.accept(field,frozen);});bulkActions.getChildren().add(button);
        }
        clear.setId("productClearSelection");clear.setText("");clear.setGraphic(new FontIcon("fth-x"));clear.setMinSize(32,38);
        bulk.setVisible(false);bulk.setManaged(false);
        var bottom=new VBox(bulk);bottom.setPadding(new Insets(14,0,0,0));bottom.managedProperty().bind(bulk.managedProperty());bottom.visibleProperty().bind(bottom.managedProperty());setBottom(bottom);
        reload.setOnAction(e->synchronize());more.setOnAction(e->load(true));
        debounce.setOnFinished(e->filterChanged());search.textProperty().addListener((o,a,b)->{if(!updating){selection.clear();updateSelection();debounce.playFromStart();}});
        category.valueProperty().addListener((o,a,b)->{if(!updating)filterChanged();});document.valueProperty().addListener((o,a,b)->{if(!updating)filterChanged();});issue.valueProperty().addListener((o,a,b)->{refreshIssueCells();if(!updating)filterChanged();});missing.setOnAction(e->filterChanged());
        pageSelection.setOnAction(e->{if(pageSelection.isSelected())selectAllResults();else{selection.clear();updateSelection();}});
        clear.setOnAction(e->{selection.clear();updateSelection();});copy.setOnAction(e->{if(onCopy!=null&&!loading&&!syncing&&!selecting)onCopy.accept(selection.freeze());});
        all.setOnAction(e->selectAllResults());applyTranslations();setShop(null);
    }
    public void setOnCopy(Consumer<List<ProductKey>> callback){onCopy=callback;updateButtons();}
    public void setOnEdit(BiConsumer<String,List<ProductKey>> callback){onEdit=callback;updateButtons();}
    public void setShop(Shop value) {
        if(disposed)return;
        String scope=value==null?"":com.vncode.app.integration.listing.ListingIdentity.fingerprint(value);
        boolean changed=shop==null||value==null||!scope.equals(shopScope);
        if(!changed)return;
        generation++;syncTicket++;syncing=false;loading=false;selecting=false;lastSync=null;issueCounts=Map.of();shopScope=scope;shop=value;debounce.stop();selection.clear();selection.switchShop(value==null?0:value.getId(),value==null?null:value.getMarketplace());
        updating=true;search.clear();category.getItems().clear();category.getItems().add(null);category.setValue(null);document.setValue(null);issue.setValue(ProductIssueFilter.ALL);missing.setSelected(false);updating=false;
        rows.getItems().clear();displayedTotal=0;count.setText(java.text.MessageFormat.format(tr("products.total"),0));cursor="";installProgress(backgroundEdits.get(shopScope));updateSelection();refresh();
    }
    private void filterChanged(){selection.clear();updateSelection();refresh();}
    public void refresh(){if(disposed)return;selecting=false;generation++;cursor="";displayedQuery=null;all.setDisable(true);load(false);}
    private void synchronize(){
        if(disposed||shop==null||syncing)return;
        var selected=shop;var owner=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        var q=displayedQuery==null?query(""):displayedQuery;long ticket=++syncTicket;syncing=true;updateButtons();rows.refresh();reload.setDisable(true);status.setText(tr("products.loading"));
        reads.submit(new ShopReadCoordinator.Key(owner.getId(),"product-sync",q.toString()),true,()->refreshService.refresh(owner,q,p->Platform.runLater(()->{
            if(!disposed&&ticket==syncTicket&&shop!=null&&com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner)))status.setText(tr("products.loading")+" "+p.completed()+"/"+p.total());
        }))).whenComplete((report,error)->Platform.runLater(()->{
            if(disposed||ticket!=syncTicket||shop==null||shop.getId()!=owner.getId()||shop.getMarketplace()!=owner.getMarketplace()||!Objects.equals(shop.getApiKey(),owner.getApiKey())||!Objects.equals(shop.getClientId(),owner.getClientId()))return;
            syncing=false;updateButtons();rows.refresh();reload.setDisable(false);
            if(error!=null){status.setText(tr("products.read_failed"));return;}
            lastSync=report;refresh();
        }));
    }
    private ProductQuery query(String next){return new ProductQuery(shop.getId(),shop.getMarketplace(),search.getText(),category.getValue()==null?List.of():List.of(category.getValue()),document.getValue(),missing.isSelected(),next,100,issue.getValue());}
    private void installScrollPaging(){
        for(var node:rows.lookupAll(".scroll-bar"))if(node instanceof ScrollBar bar&&bar.getOrientation()==Orientation.VERTICAL&&!Boolean.TRUE.equals(bar.getProperties().putIfAbsent("productPaging",true)))
            bar.valueProperty().addListener((o,a,b)->{if(b.doubleValue()>=bar.getMax()*.90&&cursor!=null&&!cursor.isEmpty()&&!loading&&!syncing&&!selecting)load(true);});
    }
    private void load(boolean append) {
        if(append&&(loading||syncing||selecting||cursor==null||cursor.isEmpty()))return;
        if(disposed||shop==null){reload.setDisable(false);status.setText(tr("products.select_shop"));more.setVisible(false);more.setManaged(false);return;}
        var q=query(append?cursor:"");var owner=shop;long epoch=generation,started=System.nanoTime();loading=true;rows.setPlaceholder(new LoadingSkeleton(tr("products.loading")));updateButtons();rows.refresh();reload.setDisable(true);more.setDisable(true);status.setText(tr("products.loading"));
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-page",q.toString()),()->reader.apply(q)).whenComplete((page,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation||shop==null||shop.getId()!=owner.getId()||shop.getMarketplace()!=owner.getMarketplace())return;
            loading=false;rows.setPlaceholder(new Label(tr("products.empty")));updateButtons();rows.refresh();reload.setDisable(syncing);more.setDisable(false);
            if(error!=null){status.setText(tr("products.read_failed"));return;}
            if(page.rows().stream().anyMatch(row->row.key().shopId()!=owner.getId()||row.key().marketplace()!=owner.getMarketplace())){status.setText(tr("products.read_failed"));return;}
            if(append)rows.getItems().addAll(page.rows());else{com.vncode.app.shared.TableRows.merge(rows.getItems(),page.rows(),ProductRow::key);all.setDisable(false);}displayedQuery=q;
            issueCounts=page.issueCounts();refreshIssueCells();
            cursor=page.nextCursor();displayedTotal=page.total();count.setText(java.text.MessageFormat.format(tr("products.total"),page.total()));more.setVisible(cursor!=null&&!cursor.isEmpty());more.setManaged(more.isVisible());status.setText(syncing?tr("products.loading"):syncStatus());updateSelection();
            RuntimeDiagnostics.afterNextPulse("products-render",started);
        }));
        if(!append&&category.getItems().size()==1)reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-categories",owner.getMarketplace().name()),()->repository.categories(owner.getId(),owner.getMarketplace())).whenComplete((values,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation||error!=null)return;updating=true;category.getItems().setAll((String)null);category.getItems().addAll(values);updating=false;
        }));
    }
    private void selectAllResults() {
        if(shop==null||displayedQuery==null||loading||syncing||selecting)return;
        if(rows.getItems().size()==displayedTotal){selection.selectPage(rows.getItems().stream().map(ProductRow::key).toList());updateSelection();return;}
        selecting=true;updateButtons();var q=displayedQuery;long epoch=generation;
        reads.submitLocal(new ShopReadCoordinator.Key(q.shopId(),"product-selection",q.toString()),()->repository.freezeProductKeys(q)).whenComplete((keys,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;selecting=false;updateButtons();if(error!=null){status.setText(tr("products.read_failed"));return;}selection.selectPage(keys);updateSelection();
        }));
    }
    private void updateSelection(){int n=selection.freeze().size();selected.setText(java.text.MessageFormat.format(tr("products.selected_count"),n));bulk.setVisible(n>0&&!pickerMode);bulk.setManaged(n>0&&!pickerMode);boolean allSelected=n>0&&n==displayedTotal;pageSelection.setSelected(allSelected);pageSelection.setIndeterminate(n>0&&!allSelected);updateButtons();rows.refresh();}
    private void updateButtons(){boolean blocked=shop==null||loading||syncing||selecting||currentEdit!=null&&currentEdit.backgroundRunning().get();boolean empty=selection.freeze().isEmpty();copy.setDisable(blocked||empty||onCopy==null);pageSelection.setDisable(blocked);clear.setDisable(blocked);all.setDisable(blocked||displayedQuery==null);for(var n:bulkActions.getChildren())if(n instanceof Button b&&b.getUserData()!=null)b.setDisable(blocked||empty||onEdit==null);}
    private void details(ProductRow row) {
        var key=row.key();long epoch=generation;reads.submitLocal(new ShopReadCoordinator.Key(key.shopId(),"product-detail",key.cardId()+":"+key.variantId()),()->repository.localSnapshot(key)).whenComplete((snapshot,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;if(error!=null||snapshot.isEmpty()){status.setText(tr("products.read_failed"));return;}
            var p=snapshot.get();var text=new StringBuilder(p.title()).append("\n\n").append(p.description()).append("\n\n");
            for(var a:p.attributes())text.append(a.name()).append(": ").append(String.join(", ",a.values())).append(' ').append(Objects.toString(a.unit(),"")).append('\n');
            text.append('\n').append(tr("products.sizes")).append(":\n");for(var v:p.variants())text.append(v.sellerArticle()).append(" · ").append(v.color()).append(" · ").append(v.size()).append(" · GTIN: ").append(v.gtinVerified()?v.gtin():tr("products.unverified")).append('\n');
            text.append('\n').append(tr("products.dimensions")).append(": ").append(p.dimensions()).append('\n');
            for(var media:p.media())text.append(tr("products.media")).append(": ").append(media.width()).append(" × ").append(media.height()).append(" px\n");
            for(var d:p.documents())text.append(d.type()).append(" · ").append(doc(d.state())).append(" · ").append(d.validUntil()).append(" · ").append(d.source()).append('\n');
            var area=new TextArea(text.toString());area.setEditable(false);area.setWrapText(true);area.setPrefSize(650,360);var dialog=new Dialog<Void>();dialog.setTitle(tr("products.details"));if(getScene()!=null)dialog.initOwner(getScene().getWindow());
            dialog.getDialogPane().setContent(area);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);DialogLayout.install(dialog.getDialogPane());dialog.show();
        }));
    }
    private final class ProductCell extends ListCell<ProductRow> {
        private final CheckBox check=new CheckBox();private final ImageView image=new ImageView();private final Label name=new Label(),meta=new Label(),article=new Label(),barcode=new Label(),paper=new Label(),material=new Label(),tnved=new Label(),genderText=new Label();
        private final HBox box;private final VBox right=new VBox(8),taxonomy=new VBox(8);private long imageRequest;
        ProductCell(){
            for(var label:List.of(meta,material,tnved,genderText))label.getStyleClass().add("text-muted");
            image.setFitWidth(84);image.setFitHeight(110);image.setPreserveRatio(true);
            var photo=new StackPane(image);photo.setMinSize(84,110);photo.setMaxSize(84,110);photo.getStyleClass().add("product-photo");
            name.setWrapText(false);name.setMaxWidth(Double.MAX_VALUE);name.getStyleClass().add("product-name");
            var middle=new VBox(3,name,meta,article,barcode);HBox.setHgrow(middle,Priority.ALWAYS);middle.setMinWidth(120);middle.setPrefWidth(580);
            for(var label:List.of(meta,article,barcode))label.setMaxWidth(Double.MAX_VALUE);
            right.getChildren().setAll(paper,material);right.setMinWidth(170);right.setPrefWidth(280);right.setMaxWidth(280);material.setWrapText(true);
            taxonomy.setMinWidth(210);taxonomy.setPrefWidth(235);taxonomy.setMaxWidth(235);
            box=new HBox(18,check,photo,middle,right);box.setAlignment(Pos.TOP_LEFT);box.getStyleClass().add("product-row");box.prefWidthProperty().bind(widthProperty().subtract(32));
            check.setMinWidth(24);check.setMaxWidth(24);HBox.setMargin(check,new Insets(42,0,0,0));
            check.setOnAction(e->{var row=getItem();if(row!=null){selection.setSelected(row.key(),check.isSelected());updateSelection();}});
            setOnMouseClicked(e->{if(e.getClickCount()==2&&getItem()!=null)details(getItem());});widthProperty().addListener((o,a,b)->adaptRow());
        }
        private void adaptRow(){
            boolean wide=getWidth()>=1100;taxonomy.getChildren().clear();right.getChildren().setAll(paper,material);
            if(wide){taxonomy.getChildren().setAll(genderText,tnved);if(!box.getChildren().contains(taxonomy))box.getChildren().add(taxonomy);}
            else{box.getChildren().remove(taxonomy);right.getChildren().addAll(genderText,tnved);}
            var row=getItem();if(row!=null)meta.setText(java.util.stream.Stream.of(row.category(),row.brand()).filter(v->v!=null&&!v.isBlank()).collect(java.util.stream.Collectors.joining(" · ")));
        }
        @Override protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);long ticket=++imageRequest;image.setImage(null);if(empty||row==null){setGraphic(null);return;}setGraphic(box);check.setDisable(loading||syncing||selecting);check.setSelected(selection.contains(row.key()));name.setText(row.title());article.setText(java.text.MessageFormat.format(tr("products.market_article"),row.key().marketplace()==com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES?"WB":"Ozon")+": "+row.marketArticle());name.setTooltip(new Tooltip(row.title()));barcode.setText(tr("products.seller_article")+": "+row.vendorArticle());paper.setText(doc(row.documentState()));styleDocument(paper,row.documentState());material.setText(row.attributesLoaded()?tr("products.material")+": "+row.material():tr("products.attributes_unloaded"));tnved.setText(row.attributesLoaded()?"TN VED: "+row.tnved():"");genderText.setText(tr("products.gender")+": "+row.gender());adaptRow();meta.setTooltip(new Tooltip(meta.getText()));barcode.setTooltip(new Tooltip(tr("products.barcode")+": "+String.join(", ",row.barcodes())+"\nGTIN: "+(row.verifiedGtin().isBlank()?tr("products.unverified"):row.verifiedGtin())));
            if(row.thumbnailUrl()!=null&&!row.thumbnailUrl().isBlank())try{thumbnails.load(URI.create(row.thumbnailUrl()),96,128).whenComplete((loaded,error)->Platform.runLater(()->{if(!disposed&&ticket==imageRequest&&getItem()==row&&error==null)image.setImage(loaded);}));}catch(IllegalArgumentException ignored){}
        }
    }
    static void styleDocument(Label label,DocumentState state){
        label.getStyleClass().removeAll("document-valid","document-pending","document-rejected","text-muted");
        String style=switch(state){case VALID->"document-valid";case PENDING,UNVERIFIED->"document-pending";case REJECTED,EXPIRED->"document-rejected";default->"text-muted";};
        label.getStyleClass().add(style);
        if(state==DocumentState.MISSING)label.setGraphic(null);
        else{var shield=new FontIcon("fth-shield");shield.getStyleClass().add(style);label.setGraphic(shield);}
        label.setAccessibleText(label.getText());
    }
    private String tr(String key){return i18n.tr(key);}
    private String syncStatus(){return lastSync==null?"":java.text.MessageFormat.format(tr("products.sync_summary"),lastSync.snapshots(),lastSync.failed());}
    private String doc(DocumentState state){return tr("products.document."+state.name().toLowerCase(Locale.ROOT));}
    private ListCell<ProductIssueFilter> issueCell() {
        return new ListCell<>() {
            private final Label name=new Label(),amount=new Label();
            private final HBox box=new HBox(8,name,amount);
            {name.setMinWidth(0);amount.setMinWidth(Region.USE_PREF_SIZE);HBox.setHgrow(name,Priority.ALWAYS);box.prefWidthProperty().bind(widthProperty().subtract(16));}
            @Override protected void updateItem(ProductIssueFilter value,boolean empty) {
                super.updateItem(value,empty);setText(null);
                if(value==null||empty){setGraphic(null);setTooltip(null);return;}
                name.setText(tr("products.issue."+value.name()));amount.setText(issueCounts.containsKey(value)?"("+issueCounts.get(value)+")":"");setGraphic(box);
                String full=name.getText()+" "+amount.getText();setTooltip(new Tooltip(full));setAccessibleText(full);
            }
        };
    }
    private void refreshIssueCells(){issue.setCellFactory(list->issueCell());issue.setButtonCell(issueCell());issueChips.forEach((kind,chip)->{chip.setText(tr("products.issue."+kind.name())+" ("+(issueCounts.containsKey(kind)?issueCounts.get(kind):"—")+")");chip.setSelected(issue.getValue()==kind);});}
    public void applyTranslations(){needsFix.setText(tr("products.needs_fix"));clear.setTooltip(new Tooltip(tr("products.clear_selection")));clear.setAccessibleText(tr("products.clear_selection"));refreshIssueCells();if(shop==null)status.setText(tr("products.select_shop"));document.setButtonCell(new ListCell<>(){@Override protected void updateItem(DocumentState item,boolean empty){super.updateItem(item,empty);setText(item==null?tr("products.documents_all"):doc(item));}});title.setText(tr("products.title"));reload.setText(tr("products.refresh"));reload.setTooltip(new Tooltip(tr("products.sync_help")));more.setText(tr("products.more"));all.setText(tr("products.all_results"));pageSelection.setText(tr("products.select_all"));missing.setText(tr("products.missing_gtin"));search.setPromptText(tr("products.search"));copy.setText(tr("products.copy"));for(var child:bulkActions.getChildren())if(child instanceof Button b&&b.getUserData()!=null)b.setText(tr("products.edit_"+b.getUserData()));updateSelection();category.setButtonCell(new ListCell<>(){@Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);setText(item==null?tr("products.categories"):item);}});rows.refresh();}
    public void dispose(){if(disposed)return;disposed=true;generation++;syncTicket++;progressDismiss.stop();debounce.stop();thumbnails.close();}
}
