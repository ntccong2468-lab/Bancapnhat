package com.vncode.app.ui.products;

import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import com.vncode.app.ui.workspace.WorkspaceDetailDrawer.Field;
import com.vncode.app.ui.workspace.WorkspaceDetailDrawer.Section;

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
    private final VBox progressFooter=new VBox();
    private final Label productHeading=new Label(),codesHeading=new Label(),variantHeading=new Label(),documentsHeading=new Label();
    private final HBox columnHeadings=new HBox(10);
    private final com.vncode.app.ui.workspace.WorkspaceDetailDrawer detailDrawer=new com.vncode.app.ui.workspace.WorkspaceDetailDrawer(listing);
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
        if(progressPill!=null){for(var node:progressPill.getChildren())if(node instanceof Label label)label.textProperty().unbind();progressFooter.getChildren().remove(progressPill);}
        currentEdit=editor;if(editor==null){updateButtons();return;}
        var text=new Label();text.textProperty().bind(editor.progressText());text.setWrapText(true);text.setMinWidth(0);text.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(text,Priority.ALWAYS);
        var spinner=new ProgressIndicator();spinner.setMaxSize(20,20);spinner.visibleProperty().bind(editor.backgroundRunning());spinner.managedProperty().bind(spinner.visibleProperty());
        var details=new Button(tr("products.details"));details.setMinWidth(Region.USE_PREF_SIZE);details.setOnAction(e->editor.showBackgroundDetails());
        progressPill=new HBox(10,spinner,text,details);progressPill.setId("productBulkProgressPill");progressPill.getStyleClass().add("task-status-pill");progressPill.setAlignment(javafx.geometry.Pos.CENTER);progressPill.setMinWidth(0);progressPill.setMaxWidth(Double.MAX_VALUE);progressPill.setMaxHeight(Region.USE_PREF_SIZE);progressFooter.getChildren().add(progressPill);
        var installedPill=progressPill;
        Runnable dismiss=()->{progressDismiss.setOnFinished(e->{if(currentEdit==editor&&!editor.backgroundRunning().get()){progressFooter.getChildren().remove(installedPill);}});progressDismiss.playFromStart();};
        editListener=(o,old,value)->{updateButtons();if(value)progressDismiss.stop();else dismiss.run();};editor.backgroundRunning().addListener(editListener);if(!editor.backgroundRunning().get())dismiss.run();updateButtons();
    }
    private final TextField search=new TextField();
    private final ComboBox<String> category=new ComboBox<>();
    private final ComboBox<DocumentState> document=new ComboBox<>();
    private final ComboBox<ProductIssueFilter> issue=new ComboBox<>();
    private Map<ProductIssueFilter,Long> issueCounts=Map.of();
    private final CheckBox missing=new CheckBox(),pageSelection=new CheckBox();
    private final Label title=new Label(),count=new Label(),status=new Label(),selected=new Label(),needsFix=new Label();
    private final Button reload=new Button(),more=new Button(),all=new Button(),copy=new Button(),clear=new Button("×"),syncDetails=new Button();
    private Dialog<Void> syncFailureDialog;
    private final FlowPane bulkActions=new FlowPane(8,6),issueBar=new FlowPane(8,6);
    private final Region bulk=new ProductActionBar(selected,bulkActions,clear);
    private final Map<ProductIssueFilter,ToggleButton> issueChips=new EnumMap<>(ProductIssueFilter.class);
    private final PauseTransition debounce=new PauseTransition(Duration.millis(300));
    private Consumer<List<ProductKey>> onCopy;
    private boolean pickerMode;
    public List<ProductKey> selectedKeys(){return selection.freeze();}
    public void useAsPicker(){pickerMode=true;copy.setVisible(false);copy.setManaged(false);updateSelection();}
    private BiConsumer<String,List<ProductKey>> onEdit;
    private ProductRefreshService.Report lastSync;
    private Shop shop;private long generation,syncTicket,displayedTotal;private String cursor="",shopScope="";private ProductQuery displayedQuery;private boolean updating,disposed,syncing,loading,selecting,searchDirty;
    public ProductsPane(){this(null,ShopReadCoordinator.shared());}
    public ProductsPane(Function<ProductQuery,ProductPage> reader,ShopReadCoordinator reads) {
        this(reader,reads,new ProductRefreshService());
    }
    public ProductsPane(Function<ProductQuery,ProductPage> reader,ShopReadCoordinator reads,ProductRefreshService refreshService) {
        this.reader=reader==null?repository::loadProductPage:reader;this.reads=Objects.requireNonNull(reads);
        this.refreshService=Objects.requireNonNull(refreshService);reload.setId("productSync");status.setId("productStatus");
        setId("productsPane");getStyleClass().addAll("products-workspace","workspace-page","workspace-sheet");setPadding(new Insets(16));
        rows.setId("productRows");rows.getStyleClass().add("dense-data-list");rows.setFixedCellSize(72);rows.setCellFactory(list->new ProductCell());
        rows.setAccessibleHelp(tr("ui.detail.open"));
        rows.setOnKeyPressed(e->{if(e.getCode()==javafx.scene.input.KeyCode.ENTER&&e.getTarget()==rows){var row=rows.getSelectionModel().getSelectedItem();if(row!=null){details(row);e.consume();}}});
        rows.skinProperty().addListener((o,a,b)->Platform.runLater(this::installScrollPaging));
        var headingSpacer=new Region();HBox.setHgrow(headingSpacer,Priority.ALWAYS);
        var heading=new HBox(10,title,count,headingSpacer,copy,reload);heading.getStyleClass().add("workspace-toolbar");heading.setAlignment(Pos.CENTER_LEFT);heading.setMinHeight(36);
        title.getStyleClass().addAll("h2","page-title");count.getStyleClass().add("text-muted");reload.getStyleClass().add("btn-primary");reload.setGraphic(new FontIcon("fth-refresh-cw"));copy.setGraphic(new FontIcon("fth-copy"));
        search.setId("productSearch");search.setMinWidth(180);search.setPrefWidth(375);
        category.setPrefWidth(134);document.setPrefWidth(263);
        category.setId("productCategoryFilter");document.setId("productDocumentFilter");
        category.setConverter(new StringConverter<>(){public String toString(String value){return value==null?tr("products.categories"):value;}public String fromString(String value){return value;}});
        document.getItems().add(null);document.getItems().addAll(DocumentState.values());
        document.setConverter(new StringConverter<>(){public String toString(DocumentState value){return value==null?tr("products.documents_all"):doc(value);}public DocumentState fromString(String value){return null;}});
        issue.setId("productIssueFilter");issue.getItems().setAll(ProductIssueFilter.values());issue.setValue(ProductIssueFilter.ALL);issue.setPrefWidth(250);
        issue.setConverter(new StringConverter<>(){public String toString(ProductIssueFilter v){return v==null?"":tr("products.issue."+v.name())+(issueCounts.containsKey(v)?" ("+issueCounts.get(v)+")":"");}public ProductIssueFilter fromString(String v){return ProductIssueFilter.ALL;}});
        pageSelection.setId("productSelectAll");
        pageSelection.setMinWidth(Region.USE_PREF_SIZE);category.setMinWidth(100);document.setMinWidth(200);
        // Filters are also shown beside the app's sidebar.  At narrower window
        // widths an HBox kept the document filter beyond the right edge, so let
        // the controls wrap without shrinking their labels below usable widths.
        var filters=new FlowPane(10,8,pageSelection,search,category,document);filters.setId("productFilters");filters.getStyleClass().add("workspace-toolbar");filters.setAlignment(Pos.CENTER_LEFT);filters.setMinWidth(0);
        needsFix.getStyleClass().add("text-muted");issueBar.getChildren().add(needsFix);issueBar.setAlignment(Pos.CENTER_LEFT);
        for(var kind:List.of(ProductIssueFilter.INVALID_TNVED,ProductIssueFilter.MISSING_BRAND,ProductIssueFilter.MISSING_MATERIAL)){
            var chip=new ToggleButton();chip.setId("productIssue_"+kind.name());chip.getStyleClass().add("product-issue-chip");chip.setOnAction(e->issue.setValue(issue.getValue()==kind?ProductIssueFilter.ALL:kind));issueChips.put(kind,chip);issueBar.getChildren().add(chip);
        }
        status.setWrapText(true);status.setMinWidth(0);status.setMaxWidth(Double.MAX_VALUE);status.setMaxHeight(Region.USE_PREF_SIZE);status.managedProperty().bind(status.textProperty().isNotEmpty());status.visibleProperty().bind(status.managedProperty());
        syncDetails.setId("productSyncDetails");syncDetails.setMinWidth(Region.USE_PREF_SIZE);syncDetails.managedProperty().bind(syncDetails.visibleProperty());syncDetails.setOnAction(e->showSyncFailures());
        var syncStatusRow=new HBox(10,status,syncDetails);syncStatusRow.setId("productSyncStatusRow");syncStatusRow.setAlignment(Pos.TOP_LEFT);syncStatusRow.setMinWidth(0);syncStatusRow.setMaxHeight(Region.USE_PREF_SIZE);HBox.setHgrow(status,Priority.ALWAYS);syncStatusRow.managedProperty().bind(status.managedProperty().or(syncDetails.visibleProperty()));syncStatusRow.visibleProperty().bind(syncStatusRow.managedProperty());
        var top=new VBox(6,heading,filters,issueBar,syncStatusRow);top.setPadding(new Insets(0,0,6,0));setTop(top);
        // Headers and virtual cells share the same column arithmetic. The narrow
        // view keeps all facts in two rows without spending height on a header.
        var leading=new Region();leading.setMinWidth(86);leading.setPrefWidth(86);leading.setMaxWidth(86);
        for(var label:List.of(productHeading,codesHeading,variantHeading,documentsHeading))label.getStyleClass().addAll("product-column-heading","text-muted");
        var headings=new ProductColumns(true,productHeading,codesHeading,variantHeading,documentsHeading);HBox.setHgrow(headings,Priority.ALWAYS);
        columnHeadings.getChildren().setAll(leading,headings);columnHeadings.setId("productColumnHeadings");columnHeadings.getStyleClass().add("product-column-headings");
        columnHeadings.setAlignment(Pos.CENTER_LEFT);columnHeadings.setPadding(new Insets(0,30,0,14));columnHeadings.setMinHeight(28);columnHeadings.setPrefHeight(28);columnHeadings.setMaxHeight(28);
        // A wrapping progress message is a measured footer, not an overlay whose
        // height can change after the list has already reserved its margin.
        progressFooter.setId("productProgressFooter");progressFooter.setPadding(new Insets(8,16,8,16));progressFooter.setMinHeight(Region.USE_PREF_SIZE);progressFooter.setMaxHeight(Region.USE_PREF_SIZE);
        progressFooter.managedProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(progressFooter.getChildren()));progressFooter.visibleProperty().bind(progressFooter.managedProperty());
        var productList=new VBox(0,columnHeadings,listing,progressFooter);productList.setMinSize(0,0);VBox.setVgrow(listing,Priority.ALWAYS);setCenter(productList);
        rows.widthProperty().addListener((o,a,b)->updateColumnLayout());updateColumnLayout();
        bulk.setId("productBulkActions");bulk.getStyleClass().add("products-bulk");
        selected.getStyleClass().add("text-bold");selected.setMinWidth(Region.USE_PREF_SIZE);bulkActions.setMinWidth(0);bulkActions.setAlignment(Pos.CENTER_LEFT);
        for(String field:List.of("brand","material","gender","tnved","documents")) {
            var button=new Button();button.setId("productEdit_"+field);button.getStyleClass().add("btn-primary");button.setDisable(true);button.setUserData(field);
            button.setGraphic(new FontIcon(switch(field){case "brand"->"fth-award";case "material"->"fth-layers";case "gender"->"fth-users";case "tnved"->"fth-hash";default->"fth-shield";}));
            button.setOnAction(event->{if(onEdit!=null&&!selection.isEmpty()&&!loading&&!syncing&&!selecting&&!searchDirty)onEdit.accept(field,selection.freeze());});bulkActions.getChildren().add(button);
        }
        clear.setId("productClearSelection");clear.setText("");clear.setGraphic(new FontIcon("fth-x"));clear.setMinSize(30,34);
        // BorderPane measures the horizontally biased action bar at the width it
        // actually receives; HBox queried a narrower preferred width and reserved
        // extra empty rows above the product list.
        var bottom=new BorderPane(bulk,null,more,null,null);BorderPane.setAlignment(more,Pos.CENTER_RIGHT);BorderPane.setMargin(more,new Insets(0,0,0,10));bottom.managedProperty().bind(bulk.managedProperty());bottom.visibleProperty().bind(bottom.managedProperty());setBottom(bottom);
        reload.setOnAction(e->synchronize());more.setOnAction(e->load(true));
        debounce.setOnFinished(e->filterChanged());search.textProperty().addListener((o,a,b)->{if(!updating){searchDirty=true;selection.clear();updateSelection();debounce.playFromStart();}});
        category.valueProperty().addListener((o,a,b)->{if(!updating)filterChanged();});document.valueProperty().addListener((o,a,b)->{if(!updating)filterChanged();});issue.valueProperty().addListener((o,a,b)->{refreshIssueCells();if(!updating)filterChanged();});missing.setOnAction(e->filterChanged());
        pageSelection.setOnAction(e->{if(pageSelection.isSelected())selectAllResults();else{selection.clear();updateSelection();}});
        clear.setOnAction(e->{selection.clear();updateSelection();});copy.setOnAction(e->{if(onCopy!=null&&!loading&&!syncing&&!selecting&&!searchDirty)onCopy.accept(selection.freeze());});
        all.setOnAction(e->selectAllResults());applyTranslations();setShop(null);
    }
    private void updateColumnLayout(){
        boolean wide=rows.getWidth()>=1034;
        columnHeadings.setVisible(wide);columnHeadings.setManaged(wide);
        double pitch=wide?72:112;if(rows.getFixedCellSize()!=pitch)rows.setFixedCellSize(pitch);
    }
    private void refreshColumnHeadings(){
        productHeading.setText(tr("products.title"));codesHeading.setText(tr("products.article"));
        variantHeading.setText(tr("products.sizes")+" · "+tr("products.material"));documentsHeading.setText(tr("copy.documents"));
    }
    /** Four fact families on desktop; the same nodes form a two-by-two grid when narrower. */
    private static final class ProductColumns extends Region {
        private static final double GAP=12,ROW_GAP=8;
        private static final double[] SHARES={.35,.20,.23,.22};
        private final boolean headings;private final Node[] fields;
        ProductColumns(boolean headings,Node... fields){this.headings=headings;this.fields=fields;getChildren().setAll(fields);setMinWidth(0);setPrefWidth(0);setMaxWidth(Double.MAX_VALUE);}
        @Override public Orientation getContentBias(){return Orientation.HORIZONTAL;}
        @Override protected double computePrefHeight(double width){
            if(headings)return 20;
            double available=width<0?getWidth():width;
            if(available>=900)return 53;
            double left=Math.max(0,(available-GAP)*.54),right=Math.max(0,available-GAP-left);
            return Math.max(fields[0].prefHeight(left),fields[3].prefHeight(right))+ROW_GAP
                    +Math.max(fields[1].prefHeight(left),fields[2].prefHeight(right));
        }
        @Override protected void layoutChildren(){
            double width=getWidth();
            if(headings||width>=900){
                // Sum: 35% + 20% + 23% + 22% of (width - three 12px gutters).
                double available=Math.max(0,width-3*GAP),x=0;
                for(int i=0;i<fields.length;i++){double part=available*SHARES[i];place(fields[i],x,0,part,getHeight());x+=part+GAP;}
            }else{
                double left=Math.max(0,(width-GAP)*.54),right=Math.max(0,width-GAP-left);
                double first=Math.max(fields[0].prefHeight(left),fields[3].prefHeight(right));
                place(fields[0],0,0,left,first);place(fields[3],left+GAP,0,right,first);
                place(fields[1],0,first+ROW_GAP,left,getHeight()-first-ROW_GAP);
                place(fields[2],left+GAP,first+ROW_GAP,right,getHeight()-first-ROW_GAP);
            }
        }
        private static void place(Node node,double x,double y,double width,double height){node.resizeRelocate(x,y,width,Math.min(Math.max(0,height),node.prefHeight(width)));}
    }
    /** Measure wrapped actions with the width remaining beside the caption and close button. */
    private static final class ProductActionBar extends Region {
        private static final double GAP=10;
        private final Label caption;private final FlowPane actions;private final Button close;
        ProductActionBar(Label caption,FlowPane actions,Button close){this.caption=caption;this.actions=actions;this.close=close;getChildren().addAll(caption,actions,close);setMinSize(0,USE_PREF_SIZE);}
        @Override public Orientation getContentBias(){return Orientation.HORIZONTAL;}
        private double actionsWidth(double width){return Math.max(1,width-snappedLeftInset()-snappedRightInset()-caption.prefWidth(-1)-close.prefWidth(-1)-2*GAP);}
        @Override protected double computePrefWidth(double height){return snappedLeftInset()+caption.prefWidth(-1)+GAP+actions.prefWidth(-1)+GAP+close.prefWidth(-1)+snappedRightInset();}
        @Override protected double computePrefHeight(double width){double available=actionsWidth(width<0?prefWidth(-1):width);return snappedTopInset()+Math.max(actions.prefHeight(available),Math.max(caption.prefHeight(-1),close.prefHeight(-1)))+snappedBottomInset();}
        @Override protected void layoutChildren(){
            double x=snappedLeftInset(),y=snappedTopInset(),h=getHeight()-y-snappedBottomInset(),cw=caption.prefWidth(-1),bw=close.prefWidth(-1);
            caption.resizeRelocate(x,y+(h-caption.prefHeight(cw))/2,cw,caption.prefHeight(cw));
            actions.resizeRelocate(x+cw+GAP,y,actionsWidth(getWidth()),h);
            close.resizeRelocate(getWidth()-snappedRightInset()-bw,y+(h-close.prefHeight(bw))/2,bw,close.prefHeight(bw));
        }
    }
    public void setOnCopy(Consumer<List<ProductKey>> callback){onCopy=callback;updateButtons();}
    public void setOnEdit(BiConsumer<String,List<ProductKey>> callback){onEdit=callback;updateButtons();}
    public void setShop(Shop value) {
        if(disposed)return;
        String scope=value==null?"":com.vncode.app.integration.listing.ListingIdentity.fingerprint(value);
        boolean changed=shop==null||value==null||!scope.equals(shopScope);
        if(!changed)return;
        if(syncFailureDialog!=null)syncFailureDialog.close();
        detailDrawer.clear();generation++;syncTicket++;syncing=false;loading=false;selecting=false;searchDirty=false;lastSync=null;issueCounts=Map.of();shopScope=scope;shop=value;debounce.stop();selection.clear();selection.switchShop(value==null?0:value.getId(),value==null?null:value.getMarketplace());
        updating=true;search.clear();category.getItems().clear();category.getItems().add(null);category.setValue(null);document.setValue(null);issue.setValue(ProductIssueFilter.ALL);missing.setSelected(false);updating=false;
        rows.getItems().clear();displayedTotal=0;count.setText(java.text.MessageFormat.format(tr("products.total"),0));cursor="";installProgress(backgroundEdits.get(shopScope));updateSelection();updateSyncDetails();refresh();
    }
    private void filterChanged(){debounce.stop();searchDirty=false;selection.clear();updateSelection();refresh();}
    public void refresh(){if(disposed)return;selecting=false;generation++;cursor="";displayedQuery=null;all.setDisable(true);load(false);}
    private void synchronize(){
        if(disposed||shop==null||syncing)return;
        var selected=shop;var owner=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        var q=displayedQuery==null?query(""):displayedQuery;long ticket=++syncTicket;syncing=true;lastSync=null;updateSyncDetails();updateButtons();reload.setDisable(true);status.setText(tr("products.loading"));
        reads.submit(new ShopReadCoordinator.Key(owner.getId(),"product-sync",q.toString()),true,()->refreshService.refresh(owner,q,p->Platform.runLater(()->{
            if(!disposed&&ticket==syncTicket&&shop!=null&&com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner)))status.setText(tr("products.loading")+" "+p.completed()+"/"+p.total());
        }))).whenComplete((report,error)->Platform.runLater(()->{
            if(disposed||ticket!=syncTicket||shop==null||shop.getId()!=owner.getId()||shop.getMarketplace()!=owner.getMarketplace()||!Objects.equals(shop.getApiKey(),owner.getApiKey())||!Objects.equals(shop.getClientId(),owner.getClientId()))return;
            syncing=false;updateButtons();reload.setDisable(false);
            if(error!=null){status.setText(tr("products.read_failed"));return;}
            lastSync=report;updateSyncDetails();refresh();
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
        var q=query(append?cursor:"");var owner=shop;long epoch=generation,started=System.nanoTime();loading=true;rows.setPlaceholder(new LoadingSkeleton(tr("products.loading")));updateButtons();reload.setDisable(true);more.setDisable(true);status.setText(tr("products.loading"));
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-page",q.toString()),()->reader.apply(q)).whenComplete((page,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation||shop==null||shop.getId()!=owner.getId()||shop.getMarketplace()!=owner.getMarketplace())return;
            loading=false;rows.setPlaceholder(new Label(tr("products.empty")));updateButtons();reload.setDisable(syncing);more.setDisable(false);
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
        if(shop==null||displayedQuery==null||loading||syncing||selecting||searchDirty)return;
        if(rows.getItems().size()==displayedTotal){selection.selectPage(rows.getItems().stream().map(ProductRow::key).toList());updateSelection();return;}
        selecting=true;updateButtons();var q=displayedQuery;long epoch=generation;
        reads.submitLocal(new ShopReadCoordinator.Key(q.shopId(),"product-selection",q.toString()),()->repository.freezeProductKeys(q)).whenComplete((keys,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;selecting=false;updateButtons();if(error!=null){status.setText(tr("products.read_failed"));return;}selection.selectPage(keys);updateSelection();
        }));
    }
    private void updateSelection(){int n=selection.size();WorkspaceMotion.selection(selected);selected.setText(java.text.MessageFormat.format(tr("products.selected_count"),n));bulk.setVisible(!pickerMode);bulk.setManaged(!pickerMode);boolean allSelected=n>0&&n==displayedTotal;pageSelection.setSelected(allSelected);pageSelection.setIndeterminate(n>0&&!allSelected);updateButtons();}
    private void updateButtons(){boolean blocked=shop==null||loading||syncing||selecting||searchDirty||currentEdit!=null&&currentEdit.backgroundRunning().get();boolean empty=selection.isEmpty();copy.setDisable(blocked||empty||onCopy==null);pageSelection.setDisable(blocked);clear.setDisable(blocked);all.setDisable(blocked||displayedQuery==null);for(var n:bulkActions.getChildren())if(n instanceof Button b&&b.getUserData()!=null)b.setDisable(blocked||empty||onEdit==null);updateVisibleChecks();}
    /** Updating selection must not recreate virtual cells or clear their already loaded images. */
    private void updateVisibleChecks(){for(var node:rows.lookupAll(".list-cell"))if(node instanceof ProductsPane.ProductCell cell)cell.updateCheck();}
    private void details(ProductRow row) {
        var key=row.key();long epoch=generation;reads.submitLocal(new ShopReadCoordinator.Key(key.shopId(),"product-detail",key.cardId()+":"+key.variantId()),()->repository.localSnapshot(key)).whenComplete((snapshot,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;if(error!=null||snapshot.isEmpty()){status.setText(tr("products.read_failed"));return;}
            var p=snapshot.get();var sections=new ArrayList<Section>();
            sections.add(new Section(p.title(),List.of(new Field(tr("copy.field.description"),p.description()))));
            sections.add(new Section(tr("ui.detail.attributes"),p.attributes().stream()
                .map(a->new Field(a.name(),String.join(", ",a.values())+" "+Objects.toString(a.unit(),""))).toList()));
            int index=0;
            for(var v:p.variants())sections.add(new Section(tr("ui.detail.variant")+" "+(++index),List.of(
                new Field(tr("products.seller_article"),v.sellerArticle()),
                new Field(tr("fbo.column.color"),v.color()),new Field(tr("fbo.column.size"),v.size()),
                new Field("GTIN",v.gtinVerified()?v.gtin():tr("products.unverified")))));
            var packageFields=new ArrayList<Field>();packageFields.add(new Field(tr("products.dimensions"),Objects.toString(p.dimensions(),"")));
            for(var media:p.media())packageFields.add(new Field(tr("products.media"),media.width()+" × "+media.height()+" px"));
            sections.add(new Section(tr("ui.detail.packaging"),packageFields));
            for(var d:p.documents())sections.add(new Section(d.type(),List.of(
                new Field(tr("ozon.dashboard.col.status"),doc(d.state())),
                new Field(tr("gs1.prefix.valid_until"),Objects.toString(d.validUntil(),"")),
                new Field(tr("tnved.source"),d.source()))));
            detailDrawer.show(tr("products.details"),sections);
        }));
    }
    private final class ProductCell extends ListCell<ProductRow> {
        private final CheckBox check=new CheckBox();private final ImageView image=new ImageView();private final Label name=new Label(),meta=new Label(),article=new Label(),barcode=new Label(),paper=new Label(),material=new Label(),tnved=new Label(),genderText=new Label(),variant=new Label();
        private final HBox box;private long imageRequest;
        ProductCell(){
            for(var label:List.of(meta,article,barcode,material,tnved,genderText,variant))label.getStyleClass().add("text-muted");
            image.setFitWidth(42);image.setFitHeight(53);image.setPreserveRatio(true);
            var photo=new StackPane(image);photo.setMinSize(42,53);photo.setMaxSize(42,53);photo.getStyleClass().add("product-photo");
            name.setWrapText(false);name.setMaxWidth(Double.MAX_VALUE);name.getStyleClass().add("product-name");
            for(var label:List.of(name,meta,article,barcode,paper,material,tnved,genderText,variant)){label.setMinWidth(0);label.setMaxWidth(Double.MAX_VALUE);}
            var identity=new VBox(1,name,meta);identity.getStyleClass().add("product-identity");
            var codes=new VBox(1,article,barcode);codes.getStyleClass().add("product-codes");
            var variants=new VBox(1,variant,material);variants.getStyleClass().add("product-variant-facts");
            var documents=new VBox(1,paper,genderText,tnved);documents.getStyleClass().add("product-document-facts");
            for(var group:List.of(identity,codes,variants,documents))group.setMinWidth(0);
            var columns=new ProductColumns(false,identity,codes,variants,documents);HBox.setHgrow(columns,Priority.ALWAYS);
            box=new HBox(10,check,photo,columns);box.setAlignment(Pos.TOP_LEFT);box.getStyleClass().add("product-row");box.prefWidthProperty().bind(widthProperty().subtract(32));
            check.setMinWidth(24);check.setMaxWidth(24);HBox.setMargin(check,new Insets(16,0,0,0));
            check.setOnAction(e->{var row=getItem();if(row!=null){selection.setSelected(row.key(),check.isSelected());updateSelection();}});
            setOnMouseClicked(e->{if(e.getClickCount()==2&&getItem()!=null)details(getItem());});widthProperty().addListener((o,a,b)->adaptRow());
        }
        private void adaptRow(){
            boolean wide=getWidth()>=1018;material.setWrapText(wide);material.setMaxHeight(wide?34:Region.USE_PREF_SIZE);
            var row=getItem();if(row!=null)meta.setText(java.util.stream.Stream.of(row.category(),row.brand()).filter(v->v!=null&&!v.isBlank()).collect(java.util.stream.Collectors.joining(" · ")));
        }
        private void updateCheck(){var row=getItem();check.setDisable(loading||syncing||selecting||searchDirty);check.setSelected(row!=null&&selection.contains(row.key()));}
        @Override protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);long ticket=++imageRequest;image.setImage(null);if(empty||row==null){setGraphic(null);return;}setGraphic(box);updateCheck();name.setText(row.title());article.setText(java.text.MessageFormat.format(tr("products.market_article"),row.key().marketplace()==com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES?"WB":"Ozon")+": "+row.marketArticle());article.setTooltip(new Tooltip(article.getText()));name.setTooltip(new Tooltip(row.title()));barcode.setText(tr("products.seller_article")+": "+row.vendorArticle());paper.setText(doc(row.documentState()));styleDocument(paper,row.documentState());material.setText(row.attributesLoaded()?tr("products.material")+": "+row.material():tr("products.attributes_unloaded"));material.setTooltip(new Tooltip(material.getText()));tnved.setText(row.attributesLoaded()?"TN VED: "+row.tnved():"");genderText.setText(tr("products.gender")+": "+row.gender());
            String size=Objects.toString(row.size(),"");if(size.isBlank())size=row.manufacturerSize();if(size.isBlank())size=row.russianSize();
            variant.setText(java.util.stream.Stream.of(row.color(),size).filter(value->value!=null&&!value.isBlank()).collect(java.util.stream.Collectors.joining(" · ")));
            variant.setTooltip(new Tooltip(variant.getText()+(row.russianSize().isBlank()?"":"\n"+tr("znack.provenance.field.variant.russian_size")+": "+row.russianSize())));
            adaptRow();meta.setTooltip(new Tooltip(meta.getText()));for(var label:List.of(paper,genderText,tnved))label.setTooltip(new Tooltip(label.getText()));barcode.setTooltip(new Tooltip(tr("products.barcode")+": "+String.join(", ",row.barcodes())+"\nGTIN: "+(row.verifiedGtin().isBlank()?tr("products.unverified"):row.verifiedGtin())));
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
    private String syncStatus(){return lastSync==null?"":java.text.MessageFormat.format(tr("products.sync_summary"),lastSync.products(),lastSync.snapshots(),lastSync.snapshots()+lastSync.failed(),lastSync.failed());}
    private void updateSyncDetails(){syncDetails.setVisible(lastSync!=null&&!syncing&&!lastSync.failures().isEmpty());}
    private void showSyncFailures(){
        if(shop==null||lastSync==null||lastSync.failures().isEmpty()||syncing)return;
        if(syncFailureDialog!=null&&syncFailureDialog.isShowing())return;
        var text=new StringBuilder();int shown=0;
        for(var failure:lastSync.failures()){
            if(failure.key().shopId()!=shop.getId()||failure.key().marketplace()!=shop.getMarketplace())continue;
            if(shown>=1000||text.length()>180_000)break;
            if(shown++>0)text.append("\n\n");
            text.append(java.text.MessageFormat.format(tr("products.sync_details_product"),detailValue(failure.key().cardId()),detailValue(failure.key().variantId()))).append('\n');
            String code=switch(failure.code()){
                case "WB_CARD_NOT_FOUND","WB_CARD_INCOMPLETE","WB_VARIANT_NOT_FOUND","PRODUCT_DETAILS_RATE_LIMIT","PRODUCT_DETAILS_AUTH_REQUIRED","PRODUCT_DETAILS_HTTP_ERROR","PRODUCT_DETAILS_TIMEOUT"->failure.code();
                default->"PRODUCT_DETAILS_UNAVAILABLE";
            };
            text.append(tr("products.sync_failure."+code));
            if(failure.httpStatus()>0)text.append(" · HTTP ").append(failure.httpStatus());
            if(!failure.missingFields().isEmpty())text.append('\n').append(java.text.MessageFormat.format(tr("products.sync_details_fields"),String.join(", ",failure.missingFields().stream().limit(16).map(ProductsPane::detailValue).toList())));
        }
        if(shown<lastSync.failures().size())text.append("\n\n").append(java.text.MessageFormat.format(tr("products.sync_details_shown"),shown,lastSync.failures().size()));
        var area=new TextArea(text.toString());area.setId("productSyncFailureDetails");area.setEditable(false);area.setWrapText(true);area.setPrefColumnCount(72);area.setPrefRowCount(18);
        var dialog=new Dialog<Void>();if(getScene()!=null&&getScene().getWindow()!=null)dialog.initOwner(getScene().getWindow());dialog.setTitle(tr("products.sync_details"));dialog.setHeaderText(tr("products.sync_details_header"));dialog.getDialogPane().setContent(area);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        syncFailureDialog=dialog;dialog.setOnHidden(e->{if(syncFailureDialog==dialog)syncFailureDialog=null;});dialog.show();
    }
    private static String detailValue(String value){if(value==null||value.isBlank())return "—";return value.codePoints().limit(256).collect(StringBuilder::new,(b,c)->b.appendCodePoint(Character.isISOControl(c)?' ':c),StringBuilder::append).toString();}
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
    public void applyTranslations(){refreshColumnHeadings();syncDetails.setText(tr("products.sync_details"));syncDetails.setAccessibleText(tr("products.sync_details_header"));syncDetails.setTooltip(new Tooltip(tr("products.sync_details_header")));updateSyncDetails();if(shop!=null&&!syncing&&lastSync!=null)status.setText(syncStatus());needsFix.setText(tr("products.needs_fix"));clear.setTooltip(new Tooltip(tr("products.clear_selection")));clear.setAccessibleText(tr("products.clear_selection"));refreshIssueCells();if(shop==null)status.setText(tr("products.select_shop"));document.setButtonCell(new ListCell<>(){@Override protected void updateItem(DocumentState item,boolean empty){super.updateItem(item,empty);setText(item==null?tr("products.documents_all"):doc(item));}});title.setText(tr("products.title"));reload.setText(tr("products.refresh"));reload.setTooltip(new Tooltip(tr("products.sync_help")));more.setText(tr("products.more"));all.setText(tr("products.all_results"));pageSelection.setText(tr("products.select_all"));missing.setText(tr("products.missing_gtin"));search.setPromptText(tr("products.search"));search.setAccessibleText(tr("products.search"));category.setAccessibleText(tr("products.categories"));document.setAccessibleText(tr("products.documents_all"));copy.setText(tr("products.copy"));for(var child:bulkActions.getChildren())if(child instanceof Button b&&b.getUserData()!=null)b.setText(tr("products.edit_"+b.getUserData()));updateSelection();category.setButtonCell(new ListCell<>(){@Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);setText(item==null?tr("products.categories"):item);}});rows.refresh();}
    public void dispose(){if(disposed)return;if(syncFailureDialog!=null)syncFailureDialog.close();detailDrawer.clear();disposed=true;generation++;syncTicket++;progressDismiss.stop();debounce.stop();thumbnails.close();}
}
