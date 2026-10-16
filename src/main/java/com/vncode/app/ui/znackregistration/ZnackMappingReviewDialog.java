package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.jobs.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.catalogsync.CatalogSyncModels;
import com.vncode.app.ui.catalogsync.CatalogSyncPane;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.*;

/** Exception review inside Znack task context; no independent GTIN tool or automatic guessing. */
public final class ZnackMappingReviewDialog {
    private ZnackMappingReviewDialog(){}
    private record Loaded(Shop shop,List<GtinSyncModels.RegisteredGtin> catalog){}
    public static void show(Window owner,JobReportRepository.Report report,JobReportRepository.Line item){
        var dialog=new Dialog<Void>();dialog.initOwner(owner);dialog.setTitle(tr("jobs.review_mapping"));
        var hint=new Label(item.product()+" / "+item.variant()+" · SKU "+item.sku()+" · "+item.article()+" · "+item.size()+" · "+item.color());hint.setWrapText(true);
        var choices=new ComboBox<GtinSyncModels.RegisteredGtin>();choices.setMaxWidth(Double.MAX_VALUE);choices.setPromptText(tr("jobs.choose_gtin"));choices.setDisable(true);
        choices.setConverter(new javafx.util.StringConverter<>(){public String toString(GtinSyncModels.RegisteredGtin g){return g==null?"":g.gtin()+" · "+g.article()+" · "+g.size()+" · "+g.color();}public GtinSyncModels.RegisteredGtin fromString(String s){throw new UnsupportedOperationException();}});
        var pane=new CatalogSyncPane();pane.setOnMutationResult((shop,result)->new SyncRunRepository().linkReviewed(shop,report.id(),result));
        var body=new VBox(10,hint,choices,pane);VBox.setVgrow(pane,Priority.ALWAYS);dialog.getDialogPane().setContent(body);dialog.getDialogPane().setPrefSize(1100,720);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);DialogLayout.install(dialog.getDialogPane());
        boolean[] closed={false};dialog.setOnHidden(e->{closed[0]=true;pane.dispose();});
        AppTaskExecutor.execute(new javafx.concurrent.Task<Loaded>(){
            protected Loaded call(){var shop=new com.vncode.app.features.shop.ShopRepository().findById(report.shopId());if(shop==null)throw new IllegalStateException("SHOP_NOT_FOUND");var source=RegisteredGtinProof.current(shop).source();var now=java.time.Instant.now();return new Loaded(shop,new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).stream().filter(g->g.source().equals(source)&&g.published()&&g.tradeUnit()&&g.syncedAt().plusSeconds(86400).isAfter(now)).toList());}
            protected void succeeded(){if(closed[0])return;var loaded=getValue();choices.getItems().setAll(loaded.catalog());choices.setDisable(false);choices.valueProperty().addListener((o,a,b)->{if(b!=null){var key=new GtinSyncModels.ProductKey(loaded.shop().getId(),loaded.shop().getMarketplace(),item.product(),item.variant());pane.open(loaded.shop(),List.of(new CatalogSyncModels.Selection(key,b.gtin())));}});}
            protected void failed(){if(!closed[0])hint.setText(tr("jobs.read_failed"));}
        },e->{if(!closed[0])hint.setText(tr("jobs.read_failed"));});dialog.show();
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
