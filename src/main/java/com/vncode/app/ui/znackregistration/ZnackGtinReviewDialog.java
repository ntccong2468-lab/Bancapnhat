package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.sync.*;
import com.vncode.app.features.catalogsync.CatalogSyncModels.Selection;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.catalogsync.CatalogSyncPane;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;

/** Part of registration: fresh source → exact selected variants → reviewed durable write. */
public final class ZnackGtinReviewDialog {
    private ZnackGtinReviewDialog(){}
    public static void show(Window window,Shop owner){
        var shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());
        var dialog=new Dialog<Void>();if(window!=null)dialog.initOwner(window);AlertService.applyTheme(dialog);
        dialog.setTitle(tr("znack.gtinflow.title")+" · "+shop.getName()+" · "+shop.getMarketplace().badge());
        var content=new Review(shop,new ZnackGtinReviewService(),new CatalogSyncPane());
        dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(1100,840);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.setOnHidden(e->content.dispose());dialog.show();
    }
    static final class Review extends VBox {
        private final Shop shop;
        private final ZnackGtinReviewService service;
        private final CatalogSyncPane plan;
        private final TableView<SyncRunRepository.Item> table=new TableView<>();
        private final Label status=new Label(tr("znack.gtinflow.loading"));
        private final Button preview=new Button(tr("catalogsync.preview")),more=new Button(tr("products.load_more")),all=new Button(tr("znack.registration.select_all")),refresh=new Button(tr("znack.registration.sync_znack"));
        private Task<?> running;
        private String run="";private int offset;private boolean busy,closed,hasMore;
        Review(Shop shop,ZnackGtinReviewService service,CatalogSyncPane plan){
            this.shop=shop;this.service=service;this.plan=plan;setId("znackGtinReview");setSpacing(8);
            status.setWrapText(true);table.setId("znackGtinReviewProducts");table.setFixedCellSize(36);table.setPrefHeight(220);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            column("Product / variant",row->row.productId()+" / "+row.variantId());column("SKU",SyncRunRepository.Item::sku);
            column(tr("products.article"),SyncRunRepository.Item::article);column(tr("fbo.column.color"),SyncRunRepository.Item::color);column(tr("fbo.column.size"),SyncRunRepository.Item::size);
            column("GTIN",SyncRunRepository.Item::gtin);column(tr("znack.registration.status"),row->tr("jobs.status."+row.state()));column(tr("jobs.reason"),SyncRunRepository.Item::error);
            var hint=new Label(tr("znack.gtinflow.hint"));hint.setWrapText(true);
            getChildren().setAll(hint,new FlowPane(8,8,refresh,all,more,preview),status,table,plan);VBox.setVgrow(plan,Priority.ALWAYS);
            table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<SyncRunRepository.Item>)c->buttons());
            refresh.setOnAction(e->reload());more.setOnAction(e->page(false));all.setOnAction(e->{table.getSelectionModel().clearSelection();for(int i=0;i<table.getItems().size()&&table.getSelectionModel().getSelectedItems().size()<1000;i++)if(ZnackGtinReviewService.eligible(table.getItems().get(i)))table.getSelectionModel().select(i);});
            preview.setOnAction(e->{var selected=List.copyOf(table.getSelectionModel().getSelectedItems());String reviewedRun=run;read(()->service.select(shop,reviewedRun,selected),rows->{plan.setOnMutationResult((owner,result)->new SyncRunRepository().linkReviewed(owner,reviewedRun,result));plan.openGtin(shop,rows);status.setText(tr("znack.gtinflow.preview_ready"));});});
            reload();
        }
        private void reload(){plan.openGtin(null,List.of());table.getItems().clear();offset=0;hasMore=false;read(()->service.prepare(shop),id->{run=id;page(true);});}
        private void page(boolean first){int start=first?0:offset;read(()->service.page(shop,run,start),rows->{if(first)table.getItems().clear();table.getItems().addAll(rows);offset=start+rows.size();hasMore=rows.size()==300;status.setText(tr("znack.gtinflow.ready")+" · "+table.getItems().size());buttons();});}
        private <T>void read(Callable<T> work,Consumer<T> accept){
            busy=true;status.setText(tr("znack.gtinflow.loading"));buttons();
            Task<T> task=new Task<>(){protected T call()throws Exception{return work.call();}};running=task;
            task.setOnSucceeded(e->{if(closed)return;busy=false;accept.accept(task.getValue());buttons();});
            Consumer<Throwable> fail=error->{if(closed)return;busy=false;status.setText(tr("znack.gtinflow.failed")+" · "+SyncFailure.of(error,0).code());buttons();};
            task.setOnFailed(e->fail.accept(task.getException()));task.setOnCancelled(e->fail.accept(new java.net.SocketTimeoutException()));AppTaskExecutor.executeCatalog(task,fail);
        }
        private void buttons(){var selected=table.getSelectionModel().getSelectedItems();preview.setDisable(busy||selected.isEmpty()||selected.size()>1000||selected.stream().anyMatch(row->!ZnackGtinReviewService.eligible(row)));refresh.setDisable(busy);all.setDisable(busy||table.getItems().isEmpty());more.setDisable(busy||!hasMore);more.setVisible(hasMore);more.setManaged(hasMore);}
        private void column(String title,Function<SyncRunRepository.Item,String> value){var column=new TableColumn<SyncRunRepository.Item,String>(title);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));table.getColumns().add(column);}
        private void dispose(){closed=true;if(running!=null)running.cancel(true);plan.dispose();}
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
