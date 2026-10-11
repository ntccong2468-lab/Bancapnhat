package com.vncode.app.ui.products;

import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Virtualized shop-scoped products. Every result is checked against its owner and generation. */
public final class ProductsPane extends BorderPane {
    private final ProductRepository repository=new ProductRepository();
    private final Function<ProductQuery,ProductPage> reader;
    private final ShopReadCoordinator reads;
    private final ThumbnailLoader thumbnails=new ThumbnailLoader();
    private final ProductSelection selection=new ProductSelection();
    private final I18nService i18n=I18nService.getInstance();
    private final ListView<ProductRow> rows=new ListView<>();
    private final TextField search=new TextField();
    private final ComboBox<String> category=new ComboBox<>();
    private final ComboBox<DocumentState> document=new ComboBox<>();
    private final CheckBox missing=new CheckBox(),pageSelection=new CheckBox();
    private final Label title=new Label(),count=new Label(),status=new Label(),selected=new Label();
    private final Button reload=new Button(),more=new Button(),all=new Button(),copy=new Button(),clear=new Button("×");
    private final FlowPane bulk=new FlowPane(8,8);
    private final PauseTransition debounce=new PauseTransition(Duration.millis(300));
    private Consumer<List<ProductKey>> onCopy;
    private BiConsumer<String,List<ProductKey>> onEdit;
    private Shop shop;private long generation;private String cursor="";private ProductQuery displayedQuery;private boolean updating,disposed;
    public ProductsPane(){this(null,ShopReadCoordinator.shared());}
    public ProductsPane(Function<ProductQuery,ProductPage> reader,ShopReadCoordinator reads) {
        this.reader=reader==null?repository::loadPage:reader;this.reads=Objects.requireNonNull(reads);
        setId("productsPane");getStyleClass().add("products-workspace");setPadding(new Insets(18));
        rows.setId("productRows");rows.setFixedCellSize(142);rows.setCellFactory(list->new ProductCell());
        var heading=new HBox(12,title,count,new Pane(),reload);HBox.setHgrow(heading.getChildren().get(3),Priority.ALWAYS);
        title.getStyleClass().add("h2");count.getStyleClass().add("text-muted");
        search.setId("productSearch");search.setMinWidth(140);HBox.setHgrow(search,Priority.ALWAYS);
        category.setPrefWidth(150);document.setPrefWidth(180);
        category.setConverter(new StringConverter<>(){public String toString(String value){return value==null?tr("products.categories"):value;}public String fromString(String value){return value;}});
        document.getItems().add(null);document.getItems().addAll(DocumentState.values());
        document.setConverter(new StringConverter<>(){public String toString(DocumentState value){return value==null?tr("products.documents_all"):doc(value);}public DocumentState fromString(String value){return null;}});
        var filters=new FlowPane(10,8,pageSelection,search,category,document,missing,all);search.setPrefWidth(310);
        var top=new VBox(14,heading,filters,status);top.setPadding(new Insets(0,0,12,0));setTop(top);setCenter(rows);
        bulk.getStyleClass().add("products-bulk");bulk.getChildren().add(selected);
        for(String field:List.of("brand","material","tnved","documents")) {
            var button=new Button();button.setId("productEdit_"+field);button.getStyleClass().add("btn-primary");button.setDisable(true);button.setUserData(field);
            button.setOnAction(event->{var frozen=selection.freeze();if(onEdit!=null&&!frozen.isEmpty())onEdit.accept(field,frozen);});bulk.getChildren().add(button);
        }
        copy.getStyleClass().add("btn-primary");bulk.getChildren().addAll(copy,clear);copy.setDisable(true);bulk.setVisible(false);bulk.setManaged(false);
        var bottom=new VBox(10,more,bulk);bottom.setPadding(new Insets(12,0,0,0));setBottom(bottom);
        reload.setOnAction(e->refresh());more.setOnAction(e->load(true));
        debounce.setOnFinished(e->refresh());search.textProperty().addListener((o,a,b)->{if(!updating)debounce.playFromStart();});
        category.valueProperty().addListener((o,a,b)->{if(!updating)refresh();});document.valueProperty().addListener((o,a,b)->{if(!updating)refresh();});missing.setOnAction(e->refresh());
        pageSelection.setOnAction(e->{for(var row:rows.getItems())selection.setSelected(row.key(),pageSelection.isSelected());updateSelection();});
        clear.setOnAction(e->{selection.clear();updateSelection();});copy.setOnAction(e->{if(onCopy!=null)onCopy.accept(selection.freeze());});
        all.setOnAction(e->selectAllResults());applyTranslations();setShop(null);
    }
    public void setOnCopy(Consumer<List<ProductKey>> callback){onCopy=callback;copy.setDisable(callback==null);}
    public void setOnEdit(BiConsumer<String,List<ProductKey>> callback){onEdit=callback;for(var child:bulk.getChildren())if(child instanceof Button b&&b.getUserData()!=null)b.setDisable(callback==null);}
    public void setShop(Shop value) {
        if(disposed)return;
        boolean changed=shop==null||value==null||shop.getId()!=value.getId()||shop.getMarketplace()!=value.getMarketplace();
        if(!changed)return;
        generation++;shop=value;debounce.stop();selection.switchShop(value==null?0:value.getId(),value==null?null:value.getMarketplace());
        updating=true;search.clear();category.getItems().clear();category.getItems().add(null);category.setValue(null);document.setValue(null);missing.setSelected(false);updating=false;
        rows.getItems().clear();count.setText("0");cursor="";updateSelection();refresh();
    }
    public void refresh(){if(disposed)return;generation++;cursor="";displayedQuery=null;all.setDisable(true);load(false);}
    private ProductQuery query(String next){return new ProductQuery(shop.getId(),shop.getMarketplace(),search.getText(),category.getValue()==null?List.of():List.of(category.getValue()),document.getValue(),missing.isSelected(),next,100);}
    private void load(boolean append) {
        if(disposed||shop==null){reload.setDisable(false);status.setText(tr("products.select_shop"));more.setVisible(false);more.setManaged(false);return;}
        var q=query(append?cursor:"");var owner=shop;long epoch=generation,started=System.nanoTime();reload.setDisable(true);more.setDisable(true);status.setText(tr("products.loading"));
        reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-page",q.toString()),()->reader.apply(q)).whenComplete((page,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation||shop==null||shop.getId()!=owner.getId()||shop.getMarketplace()!=owner.getMarketplace())return;
            reload.setDisable(false);more.setDisable(false);
            if(error!=null){status.setText(tr("products.read_failed"));return;}
            if(page.rows().stream().anyMatch(row->row.key().shopId()!=owner.getId()||row.key().marketplace()!=owner.getMarketplace())){status.setText(tr("products.read_failed"));return;}
            if(append)rows.getItems().addAll(page.rows());else{rows.getItems().setAll(page.rows());displayedQuery=q;all.setDisable(false);}
            cursor=page.nextCursor();count.setText(Long.toString(page.total()));more.setVisible(cursor!=null&&!cursor.isEmpty());more.setManaged(more.isVisible());status.setText("");updateSelection();
            RuntimeDiagnostics.afterNextPulse("products-render",started);
        }));
        if(!append&&category.getItems().size()==1)reads.submitLocal(new ShopReadCoordinator.Key(owner.getId(),"product-categories",owner.getMarketplace().name()),()->repository.categories(owner.getId(),owner.getMarketplace())).whenComplete((values,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation||error!=null)return;updating=true;category.getItems().setAll((String)null);category.getItems().addAll(values);updating=false;
        }));
    }
    private void selectAllResults() {
        if(shop==null||displayedQuery==null)return;var q=displayedQuery;long epoch=generation;
        reads.submitLocal(new ShopReadCoordinator.Key(q.shopId(),"product-selection",q.toString()),()->repository.freezeKeys(q)).whenComplete((keys,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;if(error!=null){status.setText(tr("products.read_failed"));return;}selection.selectPage(keys);updateSelection();
        }));
    }
    private void updateSelection(){int n=selection.freeze().size();selected.setText(tr("products.selected")+": "+n);bulk.setVisible(n>0);bulk.setManaged(n>0);pageSelection.setSelected(!rows.getItems().isEmpty()&&rows.getItems().stream().allMatch(row->selection.contains(row.key())));rows.refresh();}
    private void details(ProductRow row) {
        var key=row.key();long epoch=generation;reads.submitLocal(new ShopReadCoordinator.Key(key.shopId(),"product-detail",key.cardId()+":"+key.variantId()),()->repository.localSnapshot(key)).whenComplete((snapshot,error)->Platform.runLater(()->{
            if(disposed||epoch!=generation)return;if(error!=null||snapshot.isEmpty()){status.setText(tr("products.read_failed"));return;}
            var p=snapshot.get();var text=new StringBuilder(p.title()).append("\n\n").append(p.description()).append("\n\n");
            for(var a:p.attributes())text.append(a.name()).append(": ").append(String.join(", ",a.values())).append(' ').append(a.unit()).append('\n');
            text.append('\n').append(tr("products.sizes")).append(":\n");for(var v:p.variants())text.append(v.sellerArticle()).append(" · ").append(v.color()).append(" · ").append(v.size()).append(" · GTIN: ").append(v.gtinVerified()?v.gtin():tr("products.unverified")).append('\n');
            text.append('\n').append(tr("products.dimensions")).append(": ").append(p.dimensions()).append('\n');
            for(var media:p.media())text.append(tr("products.media")).append(": ").append(media.width()).append(" × ").append(media.height()).append(" px\n");
            for(var d:p.documents())text.append(d.type()).append(" · ").append(doc(d.state())).append(" · ").append(d.validUntil()).append(" · ").append(d.source()).append('\n');
            var area=new TextArea(text.toString());area.setEditable(false);area.setWrapText(true);area.setPrefSize(650,420);var dialog=new Dialog<Void>();dialog.setTitle(tr("products.details"));dialog.getDialogPane().setContent(area);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.show();
        }));
    }
    private final class ProductCell extends ListCell<ProductRow> {
        private final CheckBox check=new CheckBox();private final ImageView image=new ImageView();private final Label name=new Label(),meta=new Label(),article=new Label(),barcode=new Label(),paper=new Label(),material=new Label(),tnved=new Label();
        private final HBox box;private long imageRequest;
        ProductCell(){for(var label:List.of(meta,article,material,tnved))label.getStyleClass().add("text-muted");image.setFitWidth(82);image.setFitHeight(110);image.setPreserveRatio(true);name.setWrapText(true);name.setMaxWidth(Double.MAX_VALUE);name.getStyleClass().add("product-name");var middle=new VBox(7,name,meta,article,barcode);HBox.setHgrow(middle,Priority.ALWAYS);middle.setMinWidth(120);var right=new VBox(8,paper,material,tnved);right.setMinWidth(170);right.setMaxWidth(260);material.setWrapText(true);box=new HBox(16,check,image,middle,right);box.getStyleClass().add("product-row");box.setPadding(new Insets(12));
            check.setOnAction(e->{var row=getItem();if(row!=null){selection.setSelected(row.key(),check.isSelected());updateSelection();}});setOnMouseClicked(e->{if(e.getClickCount()==2&&getItem()!=null)details(getItem());});}
        @Override protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);long ticket=++imageRequest;image.setImage(null);if(empty||row==null){setGraphic(null);return;}setGraphic(box);check.setSelected(selection.contains(row.key()));name.setText(row.title());meta.setText(String.join(" · ",row.category(),row.brand(),row.color(),row.size()));article.setText(tr("products.article")+": "+row.vendorArticle()+" · "+row.marketArticle());barcode.setText("GTIN: "+(row.verifiedGtin().isBlank()?tr("products.unverified"):row.verifiedGtin()));paper.setText(doc(row.documentState()));paper.getStyleClass().removeAll("document-valid","text-muted");paper.getStyleClass().add(row.documentState()==DocumentState.VALID?"document-valid":"text-muted");material.setText(tr("products.material")+": "+row.material());tnved.setText("TN VED: "+row.tnved());
            if(row.thumbnailUrl()!=null&&!row.thumbnailUrl().isBlank())try{thumbnails.load(URI.create(row.thumbnailUrl()),96,128).whenComplete((loaded,error)->Platform.runLater(()->{if(!disposed&&ticket==imageRequest&&getItem()==row&&error==null)image.setImage(loaded);}));}catch(IllegalArgumentException ignored){}
        }
    }
    private String tr(String key){return i18n.tr(key);}
    private String doc(DocumentState state){return tr("products.document."+state.name().toLowerCase(Locale.ROOT));}
    public void applyTranslations(){if(shop==null)status.setText(tr("products.select_shop"));document.setButtonCell(new ListCell<>(){@Override protected void updateItem(DocumentState item,boolean empty){super.updateItem(item,empty);setText(item==null?tr("products.documents_all"):doc(item));}});title.setText(tr("products.title"));reload.setText(tr("products.refresh"));more.setText(tr("products.more"));all.setText(tr("products.all_results"));pageSelection.setText(tr("products.select_page"));missing.setText(tr("products.missing_gtin"));search.setPromptText(tr("products.search"));copy.setText(tr("products.copy"));for(var child:bulk.getChildren())if(child instanceof Button b&&b.getUserData()!=null)b.setText(tr("products.edit_"+b.getUserData()));updateSelection();category.setButtonCell(new ListCell<>(){@Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);setText(item==null?tr("products.categories"):item);}});rows.refresh();}
    public void dispose(){if(disposed)return;disposed=true;generation++;debounce.stop();thumbnails.close();}
}
