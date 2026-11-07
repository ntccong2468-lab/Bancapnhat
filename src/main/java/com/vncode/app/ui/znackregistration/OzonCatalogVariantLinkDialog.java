package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogSyncModels.Selection;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.catalogsync.CatalogSyncPane;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** Review exact native variants; a suggestion is never permission to buy or write a barcode. */
public final class OzonCatalogVariantLinkDialog {
    private OzonCatalogVariantLinkDialog(){}
    public static void show(Window window,Shop shop,List<ProductKey> keys,Runnable saved){
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON||keys.isEmpty()||keys.size()>1000)throw new IllegalArgumentException("GTIN_SELECTION_LIMIT");
        var dialog=new Dialog<Void>();if(window!=null)dialog.initOwner(window);dialog.setTitle(tr("ozon.catalog_link.title"));
        var content=new Review(shop,List.copyOf(keys),saved);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(1120,720);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);dialog.setOnCloseRequest(e->{if(content.applying)e.consume();});dialog.setOnHidden(e->content.closed=true);dialog.show();
    }
    static final class Row {
        final ProductRow product;RegisteredGtin chosen;OzonExistingCardLinkService.Review fresh;
        Row(ProductRow product){this.product=product;}
    }
    static final class Review extends VBox {
        interface Backend {
            List<ProductRow> products()throws Exception;
            List<RegisteredGtin> catalog(boolean remote)throws Exception;
            OzonExistingCardLinkService.Review read(GtinSyncModels.ProductKey key,String gtin)throws Exception;
            void approve(List<OzonExistingCardLinkService.Review> rows)throws Exception;
        }
        final Shop shop;final List<ProductKey> keys;final Runnable saved;final TableView<Row> table=new TableView<>();
        final TextField search=new TextField();final CheckBox confirmed=new CheckBox(tr("ozon.catalog_link.confirm"));final Label status=new Label();
        final Button refresh=new Button(tr("ozon.catalog_link.refresh")),suggest=new Button(tr("ozon.catalog_link.suggest")),preview=new Button(tr("ozon.catalog_link.preview")),apply=new Button(tr("ozon.catalog_link.save")),gtin=new Button(tr("ozon.catalog_link.gtin"));
        final Backend backend;List<RegisteredGtin> catalog=List.of();List<OzonExistingCardLinkService.Review> reviewed=List.of();List<Selection> approved=List.of();boolean busy,applying,closed;
        Review(Shop owner,List<ProductKey> keys,Runnable saved){
            this(owner,keys,saved,backend(owner,keys));
        }
        Review(Shop owner,List<ProductKey> keys,Runnable saved,Backend backend){
            this.backend=backend;
            this.shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());this.keys=keys;this.saved=saved;
            setId("ozonCatalogVariantReview");setSpacing(8);setPadding(new javafx.geometry.Insets(12));
            var hint=new Label(tr("ozon.catalog_link.hint"));hint.setWrapText(true);status.setWrapText(true);search.setPromptText(tr("ozon.catalog_link.search"));search.setPrefWidth(330);
            table.setId("ozonCatalogVariantPairs");table.setFixedCellSize(76);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            var nativeCol=new TableColumn<Row,Row>(tr("ozon.catalog_link.ozon"));nativeCol.setCellValueFactory(c->new ReadOnlyObjectWrapper<>(c.getValue()));nativeCol.setCellFactory(c->new TableCell<>(){protected void updateItem(Row row,boolean empty){super.updateItem(row,empty);setText(empty||row==null?null:row.fresh==null?row.product.title()+"\n"+row.product.vendorArticle()+" · "+row.product.color()+" · "+GtinVariantIdentity.primarySize(row.product):row.fresh.ozonTitle()+"\n"+row.fresh.ozonArticle()+" · "+row.fresh.ozonColor()+" · "+row.fresh.ozonSize());setWrapText(true);}});nativeCol.setPrefWidth(370);
            var target=new TableColumn<Row,Row>(tr("ozon.catalog_link.catalog"));target.setCellValueFactory(c->new ReadOnlyObjectWrapper<>(c.getValue()));target.setCellFactory(c->new TableCell<>(){protected void updateItem(Row row,boolean empty){super.updateItem(row,empty);setGraphic(null);if(empty||row==null)return;
                var combo=new ComboBox<RegisteredGtin>();combo.setMaxWidth(Double.MAX_VALUE);combo.setItems(javafx.collections.FXCollections.observableArrayList(choices(row)));combo.setConverter(new javafx.util.StringConverter<>(){public String toString(RegisteredGtin card){return label(card);}public RegisteredGtin fromString(String text){throw new UnsupportedOperationException();}});combo.setValue(row.chosen);
                combo.setOnAction(e->{row.chosen=combo.getValue();invalidateReview();});combo.setDisable(busy);setGraphic(combo);
            }});
            var verified=new TableColumn<Row,Row>(tr("ozon.catalog_link.checked"));verified.setCellValueFactory(c->new ReadOnlyObjectWrapper<>(c.getValue()));verified.setPrefWidth(260);verified.setCellFactory(c->new TableCell<>(){protected void updateItem(Row row,boolean empty){super.updateItem(row,empty);setWrapText(true);setText(empty||row==null||row.fresh==null?"":row.fresh.catalogArticle()+"\n"+row.fresh.catalogColor()+" · "+row.fresh.catalogSize()+"\nTN VED: "+row.fresh.tnved());}});table.getColumns().addAll(nativeCol,target,verified);
            confirmed.setWrapText(true);confirmed.setOnAction(e->buttons());getChildren().setAll(hint,new FlowPane(8,8,search,refresh,suggest),table,status,confirmed,new FlowPane(8,8,preview,apply,gtin));VBox.setVgrow(table,Priority.ALWAYS);
            search.textProperty().addListener((o,a,b)->table.refresh());suggest.setOnAction(e->{int count=0;for(var row:table.getItems()){var choices=choices(row);row.chosen=choices.size()==1?choices.getFirst():null;if(row.chosen!=null)count++;}invalidateReview();table.refresh();status.setText(count+" / "+table.getItems().size()+" · "+tr("ozon.catalog_link.suggestion_only"));});
            refresh.setOnAction(e->loadCatalog(true));preview.setOnAction(e->preview());apply.setOnAction(e->save());gtin.setOnAction(e->openGtin());
            work(backend::products,rows->{rows.forEach(p->table.getItems().add(new Row(p)));loadCatalog(false);},false);
        }
        void loadCatalog(boolean remote){work(()->backend.catalog(remote),cards->{catalog=cards;invalidateReview();table.refresh();status.setText(cards.size()+" · "+tr("ozon.catalog_link.catalog"));},false);}
        List<RegisteredGtin> choices(Row row){String query=GtinMatchingService.normalize(search.getText());return catalog.stream()
                .filter(c->OzonSkuGtinMatcher.sameColor(row.product.color(),c.color())&&GtinVariantIdentity.sizeMatches(c,GtinVariantIdentity.primarySize(row.product),row.product.russianSize()))
                .filter(c->query.isBlank()||GtinMatchingService.normalize(label(c)+" "+c.details().single("title")).contains(query)).toList();}
        void preview(){var pairs=new LinkedHashMap<GtinSyncModels.ProductKey,String>();for(var row:table.getItems())if(row.chosen!=null)pairs.put(new GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,row.product.key().cardId(),row.product.key().variantId()),row.chosen.gtin());
            if(pairs.isEmpty())return;var frozen=Map.copyOf(pairs);
            work(()->{var result=new ArrayList<OzonExistingCardLinkService.Review>();var codes=new HashSet<String>();for(var entry:frozen.entrySet()){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("KIZ_REQUEST_CANCELLED");if(!codes.add(RegisteredGtinProof.canonicalCode(entry.getValue())))throw new IllegalArgumentException("GTIN_MULTIPLE_OZON_VARIANTS");result.add(backend.read(entry.getKey(),entry.getValue()));
            }return List.copyOf(result);},value->{reviewed=value;for(var row:table.getItems())row.fresh=value.stream().filter(r->r.key().productId().equals(row.product.key().cardId())&&r.key().variantId().equals(row.product.key().variantId())).findFirst().orElse(null);confirmed.setSelected(false);table.refresh();status.setText(value.size()+" · "+tr("ozon.catalog_link.review_ready"));buttons();},false);
        }
        void save(){if(reviewed.isEmpty()||!confirmed.isSelected())return;var frozen=List.copyOf(reviewed);applying=true;
            work(()->{backend.approve(frozen);return frozen.stream().map(r->new Selection(r.key(),r.gtin())).toList();},value->{approved=value;reviewed=List.of();confirmed.setSelected(false);status.setText(value.size()+" · "+tr("ozon.catalog_link.saved"));saved.run();buttons();},true);
        }
        void openGtin(){if(approved.isEmpty())return;var dialog=new Dialog<Void>();if(getScene()!=null)dialog.initOwner(getScene().getWindow());dialog.setTitle(tr("ozon.catalog_link.gtin"));var pane=new CatalogSyncPane();pane.openGtin(shop,approved);dialog.getDialogPane().setContent(pane);dialog.getDialogPane().setPrefSize(1100,720);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);dialog.setOnHidden(e->pane.dispose());dialog.show();}
        void invalidateReview(){reviewed=List.of();approved=List.of();table.getItems().forEach(r->r.fresh=null);confirmed.setSelected(false);buttons();}
        void buttons(){for(var b:List.of(refresh,suggest,preview))b.setDisable(busy);apply.setDisable(busy||reviewed.isEmpty()||!confirmed.isSelected());gtin.setDisable(busy||approved.isEmpty());confirmed.setDisable(busy||reviewed.isEmpty());search.setDisable(busy);table.setDisable(busy);}
        <T>void work(Callable<T> call,Consumer<T> accept,boolean mutation){if(closed)return;busy=true;buttons();status.setText(tr("ozon.catalog_link.reading"));
            java.util.concurrent.CompletableFuture<T> future;
            try{future=mutation?SellerMutationExecutor.submit(shop,call):ShopReadCoordinator.shared().submit(new ShopReadCoordinator.Key(shop.getId(),"ozon-catalog-variant",UUID.randomUUID().toString()),true,call);}catch(RuntimeException full){busy=false;applying=false;status.setText(FriendlyErrorService.format(full));buttons();return;}
            future.whenComplete((result,error)->Platform.runLater(()->{busy=false;applying=false;if(closed)return;buttons();if(error!=null)status.setText(FriendlyErrorService.format(error));else accept.accept(result);}));
        }
    }
    private static Review.Backend backend(Shop shop,List<ProductKey> keys){
        var service=new OzonExistingCardLinkService();
        return new Review.Backend(){
            public List<ProductRow> products()throws Exception{return rows(shop,keys);}
            public List<RegisteredGtin> catalog(boolean remote)throws Exception{
                if(remote){var cards=NationalCatalogGtinSource.forRegistrationLookup(shop).read(shop.getId());new GtinSyncRepository().mergeCatalog(shop.getId(),Marketplace.OZON,cards);}
                String source=RegisteredGtinProof.source(shop);return new GtinSyncRepository().catalog(shop.getId(),Marketplace.OZON).stream().filter(c->c.published()&&c.tradeUnit()&&source.equals(c.source())).toList();
            }
            public OzonExistingCardLinkService.Review read(GtinSyncModels.ProductKey key,String gtin)throws Exception{return service.read(shop,key,gtin);}
            public void approve(List<OzonExistingCardLinkService.Review> rows)throws Exception{service.approveBatch(shop,rows);}
        };
    }
    static List<ProductRow> rows(Shop shop,List<ProductKey> keys)throws Exception{
        ListingIdentity.require(shop);var selected=Set.copyOf(keys);for(var key:keys)ListingIdentity.require(shop,key);
        var skus=new LinkedHashSet<String>();try(var c=Database.getConnection()){
            for(int i=0;i<keys.size();i+=400){var part=keys.subList(i,Math.min(i+400,keys.size()));try(var p=c.prepareStatement("SELECT product_id,offer_id,sku FROM ozon_products WHERE shop_id=? AND archived=0 AND product_id IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+")")){
                p.setInt(1,shop.getId());for(int n=0;n<part.size();n++)p.setString(n+2,part.get(n).cardId());try(var r=p.executeQuery()){while(r.next())if(selected.contains(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2))))skus.add(r.getString(3));}
            }}
        }
        var result=new ArrayList<ProductRow>();for(var p:new OzonCatalogRepository().findBySkus(shop.getId(),skus).values())if(selected.contains(new ProductKey(shop.getId(),Marketplace.OZON,p.productId(),p.offerId())))
            result.add(new ProductRow(new ProductKey(shop.getId(),Marketplace.OZON,p.productId(),p.offerId()),p.name(),p.article(),p.productId(),p.category(),"",p.color(),p.size(),p.barcodes(),"",p.primaryImageUrl(),"","",DocumentState.UNVERIFIED,p.gender(),true,p.russianSize(),p.manufacturerSize()));
        if(result.size()!=keys.size())throw new IllegalArgumentException("OZON_PRODUCT_IDENTITY_NOT_READ");return List.copyOf(result);
    }
    private static String label(RegisteredGtin c){return c==null?"":c.article()+" · "+c.color()+" · "+c.size()+" · GTIN "+c.gtin();}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
